// Package agent - 受控端环境文件 MD5 增量同步（platform-env-ota-realtime D4）。
// 仿 public_image_syncer：拉 /api/agent/env/list，对比本地 Env 文件夹 MD5，
// 缺失/不一致者经 /agent/file/** 下载并校验。
package agent

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/filetransfer"
)

// EnvSyncer 环境文件定时同步器
type EnvSyncer struct {
	cfg    *config.Config
	stopCh chan struct{}
}

// NewEnvSyncer 创建同步器
func NewEnvSyncer(cfg *config.Config) *EnvSyncer {
	return &EnvSyncer{
		cfg:    cfg,
		stopCh: make(chan struct{}),
	}
}

// Start 启动定时同步（启动时一次 + 每隔固定时间，间隔复用 PublicImageSyncInterval）
func (s *EnvSyncer) Start() {
	interval := time.Duration(s.cfg.PublicImageSyncInterval) * time.Second
	if interval <= 0 {
		interval = 1 * time.Hour
	}

	go func() {
		if err := s.RunNow(); err != nil {
			log.Printf("[env-sync] 启动同步失败: %v", err)
		}
	}()

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	log.Printf("[env-sync] 定时同步已启动，间隔: %v", interval)
	for {
		select {
		case <-ticker.C:
			if err := s.RunNow(); err != nil {
				log.Printf("[env-sync] 同步失败: %v", err)
			}
		case <-s.stopCh:
			log.Println("[env-sync] 已停止")
			return
		}
	}
}

// Stop 停止同步
func (s *EnvSyncer) Stop() {
	close(s.stopCh)
}

// RunNow 立即执行一次环境文件同步（env.sync 命令亦调此）
func (s *EnvSyncer) RunNow() error {
	cfg := config.Get()
	if cfg.InstanceNumber == "" || cfg.AgentToken == "" {
		return fmt.Errorf("未注册，跳过环境文件同步")
	}
	envDir := cfg.EnvDir()
	if err := os.MkdirAll(envDir, 0755); err != nil {
		return fmt.Errorf("创建 Env 目录失败: %w", err)
	}

	listURL := cfg.ServerURL + "/api/agent/env/list"
	req := newRequest("GET", listURL, cfg.AgentToken, cfg.InstanceNumber)
	resp, err := httpClient().Do(req)
	if err != nil {
		return fmt.Errorf("查询环境文件清单失败: %w", err)
	}
	defer resp.Body.Close()

	var result struct {
		Code int `json:"code"`
		Data []struct {
			Filename string `json:"filename"`
			Md5      string `json:"md5"`
			Size     int64  `json:"size"`
			Path     string `json:"path"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("解析环境文件清单失败: %w", err)
	}

	// 管理端文件清单（filename -> 远端条目），用于删除本地多余文件（严格一致）
	remoteFiles := make(map[string]struct{}, len(result.Data))
	for i := range result.Data {
		remoteFiles[result.Data[i].Filename] = struct{}{}
	}
	// 删除本地 Env 文件夹中、管理端已不存在的文件（与管理端严格一致）
	if entries, err := os.ReadDir(envDir); err == nil {
		for _, e := range entries {
			if e.IsDir() {
				continue
			}
			if _, ok := remoteFiles[e.Name()]; !ok {
				if err := os.Remove(filepath.Join(envDir, e.Name())); err == nil {
					log.Printf("[env-sync] 已删除本地多余文件: %s", e.Name())
				}
			}
		}
	}

	synced := 0
	for _, f := range result.Data {
		if f.Filename == "" || f.Path == "" {
			continue
		}
		localPath := filepath.Join(envDir, f.Filename)
		// 已存在且 MD5 一致 -> 跳过
		if localMd5, err := md5File(localPath); err == nil && localMd5 == f.Md5 {
			continue
		}
		// 经 file-transfer 下载（传输层 SHA-256 校验）
		// transferID 仅用 hex 随机串，避免文件名含空格/特殊字符破坏下载 URL（如 Docker Desktop Installer.exe）
		transferID := fmt.Sprintf("env-%s-%d", randomHex(8), time.Now().UnixNano())
		downloader := filetransfer.NewDownloader(cfg.ServerURL, cfg.AgentToken, cfg.InstanceNumber, 0)
		if err := downloader.Download(f.Path, localPath, transferID, "env", nil); err != nil {
			log.Printf("[env-sync] 下载 %s 失败: %v", f.Filename, err)
			continue
		}
		// 应用层 MD5 校验（与清单比对）
		if actual, err := md5File(localPath); err != nil || actual != f.Md5 {
			log.Printf("[env-sync] %s MD5 校验失败: 期望 %s 实际 %v", f.Filename, f.Md5, actual)
			os.Remove(localPath)
			continue
		}
		synced++
		log.Printf("[env-sync] 已同步 %s (md5=%s)", f.Filename, f.Md5)
	}
	log.Printf("[env-sync] 同步完成: %d 个文件", synced)
	return nil
}

// handleEnvSync 处理 env.sync 命令：立即执行一次环境文件同步。
func (e *Executor) handleEnvSync(cmd *Command) (string, error) {
	if e.envSyncer == nil {
		return "", fmt.Errorf("环境文件同步器未初始化")
	}
	if err := e.envSyncer.RunNow(); err != nil {
		return "", err
	}
	return "synced", nil
}

// md5File 计算文件 MD5（十六进制）
func md5File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := md5.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
