// Package agent - 镜像命令处理器（任务 9.2、9.3、9.6、9.8）
package agent

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/filetransfer"
)

// handleImageCommit 容器 commit 为镜像并导出 tar（任务 9.2、9.3）
// platform-refinements 6.5：接收 project/note/ownerWorkerId，tar 命名 工号-项目-镜像名-标签-备注-随机串，
// 使用管理端下发的 transferId（编码 imageId），回传完成才返回成功。
func (e *Executor) handleImageCommit(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	containerID, _ := cmd.Payload["containerId"].(string)
	imageName, _ := cmd.Payload["imageName"].(string)
	imageTag, _ := cmd.Payload["imageTag"].(string)
	if imageTag == "" {
		imageTag = "latest"
	}
	if containerID == "" || imageName == "" {
		return "", fmt.Errorf("containerId 或 imageName 为空")
	}
	project, _ := cmd.Payload["project"].(string)
	note, _ := cmd.Payload["note"].(string)
	ownerWorkerId, _ := cmd.Payload["ownerWorkerId"].(string)
	transferID, _ := cmd.Payload["transferId"].(string)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	// 1. docker commit
	imageRef := imageName + ":" + imageTag
	commitID, err := e.docker.CommitContainer(ctx, containerID, imageName, imageTag)
	if err != nil {
		return "", fmt.Errorf("commit 失败: %w", err)
	}
	log.Printf("[image] commit 成功: %s -> %s", containerID, commitID)

	// 2. docker save 导出 tar，文件命名 工号-项目-镜像名-标签-备注-随机串（各段 sanitize）
	tarName := buildCommitTarName(ownerWorkerId, project, imageName, imageTag, note) + ".tar"
	tarPath := filepath.Join(os.TempDir(), tarName)
	if err := e.docker.SaveImage(ctx, imageRef, tarPath); err != nil {
		return "", fmt.Errorf("save 失败: %w", err)
	}
	log.Printf("[image] save 成功: %s", tarPath)

	// 3. 上传 tar 至管理端（任务 9.3，复用 file-transfer）；transferId 由管理端下发（编码 imageId）
	if transferID == "" {
		transferID = fmt.Sprintf("img-%d", time.Now().UnixNano())
	}

	cfg := config.Get()
	uploader := filetransfer.NewUploader(cfg.ServerURL, cfg.AgentToken, cfg.InstanceNumber, 0)
	err = uploader.Upload(tarPath, transferID, "image", func(done, total int, doneBytes, totalBytes int64) {
		log.Printf("[image] 上传进度: %d/%d 块 (%d/%d bytes)", done, total, doneBytes, totalBytes)
	})
	if err != nil {
		return "", fmt.Errorf("上传 tar 失败: %w", err)
	}

	// 清理临时 tar
	os.Remove(tarPath)

	return fmt.Sprintf(`{"imageRef":"%s","transferId":"%s"}`, imageRef, transferID), nil
}

// buildCommitTarName 生成 commit 镜像 tar 文件名（去扩展名）：工号-项目-镜像名-标签-备注-随机串。
// 各段 sanitize：去路径分隔符与控制字符（保留中文与常规符号），空段以 x 占位避免连续分隔符。
func buildCommitTarName(workerId, project, imageName, imageTag, note string) string {
	random := randomHex(4)
	return strings.Join([]string{
		sanitizeSegment(workerId),
		sanitizeSegment(project),
		sanitizeSegment(imageName),
		sanitizeSegment(imageTag),
		sanitizeSegment(note),
		random,
	}, "-")
}

// sanitizeSegment 去除路径分隔符、冒号与控制字符，避免文件名非法或路径穿越。
func sanitizeSegment(s string) string {
	s = strings.TrimSpace(s)
	var b strings.Builder
	for _, r := range s {
		if r == '/' || r == '\\' || r == ':' || r < 0x20 || r == 0x7f {
			continue
		}
		b.WriteRune(r)
	}
	out := b.String()
	if out == "" {
		out = "x"
	}
	return out
}

// randomHex 生成 n 字节的随机十六进制串（避免重名）。
func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
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
