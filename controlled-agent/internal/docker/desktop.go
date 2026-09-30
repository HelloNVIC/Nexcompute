// Package docker - Docker Desktop 启动与看门狗。
// 环境准备"修改 Docker Engine 配置"步骤（gui）与常驻看门狗共用启动逻辑；
// 看门狗周期检查 daemon 可达性，不可达（Docker Desktop 意外退出/未自启）时自动拉起。
package docker

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/nexcompute/controlled-agent/internal/executil"
)

// dockerDesktopCandidates Docker Desktop.exe 常见安装路径（按优先级）。
func dockerDesktopCandidates() []string {
	candidates := []string{
		`C:\Program Files\Docker\Docker\Docker Desktop.exe`,
	}
	if pf := os.Getenv("ProgramFiles"); pf != "" {
		candidates = append(candidates, filepath.Join(pf, "Docker", "Docker", "Docker Desktop.exe"))
	}
	if pf86 := os.Getenv("ProgramFiles(x86)"); pf86 != "" {
		candidates = append(candidates, filepath.Join(pf86, "Docker", "Docker", "Docker Desktop.exe"))
	}
	return candidates
}

// StartDockerDesktop 多路径探测并启动 Docker Desktop（D6；与看门狗共用）。
// 找不到可执行文件或启动失败时返回错误。
func StartDockerDesktop() error {
	var lastErr error
	for _, p := range dockerDesktopCandidates() {
		if _, err := os.Stat(p); err != nil {
			continue
		}
		if err := executil.StartDetached(p); err != nil {
			lastErr = err
			continue
		}
		return nil
	}
	if lastErr != nil {
		return lastErr
	}
	return fmt.Errorf("未在常见路径找到 Docker Desktop.exe")
}

// StartDockerDesktopWatchdog 启动 Docker Desktop 看门狗：每 10 分钟 Ping 一次本地
// daemon，不可达则自动拉起 Docker Desktop。启动后不等待就绪，下个周期自然复查。
// 首查延迟 2 分钟：开机自启场景给 Docker Desktop 正常启动留时间，避免误拉起。
// m 为 nil 时（启动期初始化失败）自行重建客户端，仍失败则放弃并记日志。
func StartDockerDesktopWatchdog(m *Manager) {
	if m == nil {
		var err error
		m, err = NewManager()
		if err != nil {
			log.Printf("[docker-watchdog] 初始化 Docker 客户端失败，看门狗不启动: %v", err)
			return
		}
	}
	go func() {
		time.Sleep(2 * time.Minute)
		watchdogCheck(m)
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			watchdogCheck(m)
		}
	}()
}

// watchdogCheck 单次检查：daemon 可达则无事；不可达则尝试启动 Docker Desktop。
func watchdogCheck(m *Manager) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, pingErr := m.cli.Ping(ctx)
	if pingErr == nil {
		return // daemon 正常
	}
	log.Printf("[docker-watchdog] Docker daemon 不可达（%v），尝试启动 Docker Desktop", pingErr)
	if err := StartDockerDesktop(); err != nil {
		log.Printf("[docker-watchdog] 启动 Docker Desktop 失败: %v", err)
		return
	}
	log.Printf("[docker-watchdog] 已拉起 Docker Desktop，等待其就绪（下个周期复查）")
}
