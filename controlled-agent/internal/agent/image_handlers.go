// Package agent - 镜像命令处理器（任务 9.2、9.3、9.6、9.8）
package agent

import (
	"bufio"
	"context"
	cryptoRand "crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/filetransfer"
)

// handleImageCommit 容器 commit 为镜像并推送私有仓库（任务 9.2；registry-image-distribution D4）。
// 流程：docker commit（本地 ref=repo:tag）-> docker tag {registryUrl}/repo:tag -> docker push（重试 ≤3）。
// repo 名由管理端按下发规则拼好（工号-项目-镜像名-标签-备注-随机串，sanitize 为合法 docker repo），tag 固定 latest。
// 不再 docker save + file-transfer 回传 tar；push 全部完成才返回成功。
func (e *Executor) handleImageCommit(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	containerID, _ := cmd.Payload["containerId"].(string)
	repo, _ := cmd.Payload["repoName"].(string)
	tag, _ := cmd.Payload["tag"].(string)
	registryURL, _ := cmd.Payload["registryUrl"].(string)
	if tag == "" {
		tag = "latest"
	}
	if containerID == "" || repo == "" || registryURL == "" {
		return "", fmt.Errorf("containerId/repoName/registryUrl 为空")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute) // D4：push 大镜像
	defer cancel()

	// 1. docker commit（本地 ref = repo:tag）
	commitID, err := e.docker.CommitContainer(ctx, containerID, repo, tag)
	if err != nil {
		return "", fmt.Errorf("commit 失败: %w", err)
	}
	log.Printf("[image] commit 成功: %s -> %s", containerID, commitID)

	// 2. docker tag 指向私有仓库完整引用
	localRef := repo + ":" + tag
	registryRef := registryURL + "/" + localRef
	if err := e.docker.TagImage(ctx, localRef, registryRef); err != nil {
		return "", fmt.Errorf("tag 失败: %w", err)
	}
	log.Printf("[image] tag 成功: %s -> %s", localRef, registryRef)

	// 3. docker push（insecure HTTP 无认证；重试 ≤3，对齐 build.ps1 对 insecure registry EOF 重试经验）
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		lastErr = e.pushToCompletion(ctx, cmd.ID, registryRef)
		if lastErr == nil {
			break
		}
		log.Printf("[image] push 第 %d 次失败: %v", attempt, lastErr)
	}
	if lastErr != nil {
		return "", fmt.Errorf("push 失败（已重试 3 次）: %w", lastErr)
	}
	log.Printf("[image] push 成功: %s", registryRef)

	// 4. 查询镜像大小回传（管理端记录 sizeBytes）
	sizeBytes, err := e.docker.ImageSize(ctx, registryRef)
	if err != nil {
		log.Printf("[image] 查询镜像大小失败（忽略）: %v", err)
		sizeBytes = 0
	}

	return fmt.Sprintf(`{"repo":%q,"tag":%q,"imageRef":%q,"sizeBytes":%d}`,
		repo, tag, registryRef, sizeBytes), nil
}

// pushToCompletion 执行一次 push 并读进度流至 EOF（全部层推送完成才算成功）。
// 流为 JSON 行（docker push progress），含 error 字段时视为失败。
func (e *Executor) pushToCompletion(ctx context.Context, commandID, registryRef string) error {
	reader, err := e.docker.PushImage(ctx, registryRef)
	if err != nil {
		return err
	}
	defer reader.Close()
	return consumeImageProgress(reader, func(_ jsonStreamItem) {
		// commit push 无实时进度消费方（管理端同步等待），仅丢弃行保持流读取
	})
}

// jsonStreamItem docker pull/push 进度流的一行（部分字段按需取用）。
type jsonStreamItem struct {
	ID             string `json:"id"`
	Status         string `json:"status"`
	ProgressDetail struct {
		Current int64 `json:"current"`
		Total   int64 `json:"total"`
	} `json:"progressDetail"`
	Error string `json:"error"`
}

// consumeImageProgress 逐行解析 docker 镜像进度流；出现 error 字段即失败返回。
// onItem 回调用于 pull 场景的进度回传（push 场景传 noop）。
func consumeImageProgress(reader io.Reader, onItem func(jsonStreamItem)) error {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var item jsonStreamItem
		if err := json.Unmarshal(line, &item); err != nil {
			continue // 非 JSON 行（如空行）跳过
		}
		if item.Error != "" {
			return fmt.Errorf("%s", item.Error)
		}
		if onItem != nil {
			onItem(item)
		}
	}
	return scanner.Err()
}

// randomHex 生成 n 字节的随机十六进制串（env_syncer/upgrade 等复用）。
func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := cryptoRand.Read(b); err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}

// handleImagePull 从私有仓库拉取镜像（registry-image-distribution D5，命令 image.pull）。
// 镜像已本地存在 -> already_exists；否则 docker pull 解析 JSON 进度流，
// 节流回传 SendProgress（Stage=pulling、Percent 按各层字节估算、Text=层状态）。
func (e *Executor) handleImagePull(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	imageRef, _ := cmd.Payload["imageRef"].(string)
	if imageRef == "" {
		return "", fmt.Errorf("imageRef 为空")
	}

	// 已本地存在则跳过（与 image.load 行为一致）
	checkCtx, checkCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer checkCancel()
	if exists, _ := e.docker.ImageExists(checkCtx, imageRef); exists {
		return "already_exists", nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	reader, err := e.docker.PullImage(ctx, imageRef)
	if err != nil {
		return "", friendlyPullError(err)
	}
	defer reader.Close()

	// 逐层进度估算：各层 current/total 求和（无 total 的行不参与），节流 ≥200ms 或状态变化回传
	type layerState struct {
		current int64
		total   int64
		status  string
	}
	layers := make(map[string]*layerState)
	lastSent := time.Now()

	err = consumeImageProgress(reader, func(item jsonStreamItem) {
		if item.ID == "" {
			return
		}
		st, ok := layers[item.ID]
		if !ok {
			st = &layerState{}
			layers[item.ID] = st
		}
		statusChanged := st.status != item.Status
		st.status = item.Status
		if item.ProgressDetail.Total > 0 {
			st.current = item.ProgressDetail.Current
			st.total = item.ProgressDetail.Total
		}
		// 节流：≥200ms 或状态变化（如 Downloading -> Extracting/Pull complete）才回传
		if !statusChanged && time.Since(lastSent) < 200*time.Millisecond {
			return
		}
		lastSent = time.Now()

		var done, total int64
		for _, l := range layers {
			if l.total > 0 {
				done += l.current
				total += l.total
			}
		}
		percent := 0
		if total > 0 {
			percent = int(done * 100 / total)
		}
		e.sendProgressText(cmd.ID, StagePulling, percent, formatLayerText(item))
	})
	if err != nil {
		return "", friendlyPullError(err)
	}
	log.Printf("[image] pull 成功: %s", imageRef)
	return "pulled", nil
}

// formatLayerText 拼分层文本（如 "a1b2c3: Downloading 45%" / "a1b2c3: Pull complete"）。
func formatLayerText(item jsonStreamItem) string {
	if item.ID == "" {
		return item.Status
	}
	if item.ProgressDetail.Total > 0 {
		pct := item.ProgressDetail.Current * 100 / item.ProgressDetail.Total
		return fmt.Sprintf("%s: %s %d%%", item.ID, item.Status, pct)
	}
	return fmt.Sprintf("%s: %s", item.ID, item.Status)
}

// friendlyPullError 对典型 daemon 未配 insecure-registries 的 HTTPS/HTTP 类错误给出中文提示（D5）。
func friendlyPullError(err error) error {
	msg := err.Error()
	lower := strings.ToLower(msg)
	switch {
	case strings.Contains(lower, "server gave http response to https client"),
		strings.Contains(lower, "http: server gave http response to https client"):
		return fmt.Errorf("拉取失败: 仓库为 HTTP 但 Docker 以 HTTPS 访问，需在 Docker daemon 配置 insecure-registries 后重启 Docker（详见环境准备-配置私有镜像仓库）: %w", err)
	case strings.Contains(lower, "x509"), strings.Contains(lower, "tls"):
		return fmt.Errorf("拉取失败: TLS 证书校验失败，需在 Docker daemon 配置 insecure-registries 后重启 Docker: %w", err)
	default:
		return fmt.Errorf("pull 镜像失败: %w", err)
	}
}

// handleImageLoad 下载 tar 并 docker load（任务 9.6）
func (e *Executor) handleImageLoad(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	sourcePath, _ := cmd.Payload["sourcePath"].(string)
	transferID, _ := cmd.Payload["transferId"].(string)
	imageRef, _ := cmd.Payload["imageRef"].(string)

	// 检查本地是否已有此镜像（避免重复传输）
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if exists, _ := e.docker.ImageExists(ctx, imageRef); exists {
		return "already_exists", nil
	}

	// 下载 tar
	cfg := config.Get()
	localPath := filepath.Join(os.TempDir(), filepath.Base(sourcePath))
	downloader := filetransfer.NewDownloader(cfg.ServerURL, cfg.AgentToken, cfg.InstanceNumber, 0)
	err := downloader.Download(sourcePath, localPath, transferID, "image", func(done, total int, doneBytes, totalBytes int64) {
		log.Printf("[image] 下载进度: %d/%d 块 (%d/%d bytes)", done, total, doneBytes, totalBytes)
	})
	if err != nil {
		return "", fmt.Errorf("下载 tar 失败: %w", err)
	}
	defer os.Remove(localPath)

	// docker load
	loadCtx, loadCancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer loadCancel()
	if _, err := e.docker.LoadImage(loadCtx, localPath); err != nil {
		return "", fmt.Errorf("load 失败: %w", err)
	}

	log.Printf("[image] load 成功: %s", imageRef)
	return "loaded", nil
}

// handleImageSyncPublic 公共镜像同步（任务 9.8）
// 查询管理端公共镜像列表，对本地缺失的下载并 load
func (e *Executor) handleImageSyncPublic(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	cfg := config.Get()
	// 查询公共镜像列表（platform-refinements：改用受控端专用 /agent/images/public/list，
	// 置于 /agent/** 鉴权白名单，避免原 /images/public/list 被 JWT 拦截返回空体致 EOF）
	listURL := cfg.ServerURL + "/api/agent/images/public/list"
	req := newRequest("GET", listURL, cfg.AgentToken, cfg.InstanceNumber)
	resp, err := httpClient().Do(req)
	if err != nil {
		return "", fmt.Errorf("查询公共镜像列表失败: %w", err)
	}
	defer resp.Body.Close()

	var result struct {
		Code int `json:"code"`
		Data []struct {
			ID       int64  `json:"id"`
			Name     string `json:"name"`
			Tag      string `json:"tag"`
			TarPath  string `json:"tarPath"`
			Checksum string `json:"checksum"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", fmt.Errorf("解析镜像列表失败: %w", err)
	}

	synced := 0
	for _, img := range result.Data {
		imageRef := img.Name + ":" + img.Tag
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		exists, _ := e.docker.ImageExists(ctx, imageRef)
		cancel()
		if exists {
			continue
		}

		// 下载并 load
		transferID := fmt.Sprintf("public-%d-%d", img.ID, time.Now().UnixNano())
		payload := map[string]any{
			"sourcePath": img.TarPath,
			"transferId": transferID,
			"imageRef":   imageRef,
		}
		if _, err := e.handleImageLoad(&Command{Payload: payload}); err != nil {
			log.Printf("[image] 同步公共镜像 %s 失败: %v", imageRef, err)
			continue
		}
		synced++
	}

	return fmt.Sprintf("synced %d images", synced), nil
}
