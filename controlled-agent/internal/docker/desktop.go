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

// 看门狗参数（agent-defaults：10 分钟提速至 30 秒；常量化便于测试与调整）。
const (
	watchdogInterval      = 30 * time.Second  // 检查周期
	watchdogStartCooldown = 60 * time.Second  // 拉起冷却：两次拉起尝试的最小间隔
	watchdogFailLogEvery  = 20                // 连续拉起失败每 N 次记一条日志（限流）
)

// dockerDesktopCandidates Docker Desktop.exe 常见安装路径（按优先级）。
// 覆盖机器级安装（Program Files）与两种 per-user 安装形态：
//   - %LOCALAPPDATA%\Docker\Docker\（经典 per-user）
//   - %LOCALAPPDATA%\Programs\DockerDesktop\（新版安装器形态，实测
//     C:\Users\Admin\AppData\Local\Programs\DockerDesktop）
// agent-defaults 实测 DESKTOP-9IQCUKC per-user 安装不在原候选，致看门狗
// "未在常见路径找到 Docker Desktop.exe"。
func dockerDesktopCandidates() []string {
	candidates := []string{
		`C:\Program Files\Docker\Docker\Docker Desktop.exe`,
	}
	if la := os.Getenv("LOCALAPPDATA"); la != "" {
		candidates = append(candidates,
			filepath.Join(la, "Docker", "Docker", "Docker Desktop.exe"),
			filepath.Join(la, "Programs", "DockerDesktop", "Docker Desktop.exe"),
		)
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

// StartDockerDesktopWatchdog 启动 Docker Desktop 看门狗：每 30 秒 Ping 一次本地
// daemon，不可达则自动拉起 Docker Desktop（agent-defaults：自启动即检查，不设首查延迟；
// 拉起冷却覆盖开机/启动期，防进程风暴）。m 为 nil 时（启动期初始化失败）自行重建客户端，
// 仍失败则放弃并记日志。
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
		st := &watchdogState{wasHealthy: true} // 假定健康直至首次检测证明不可达，避免启动期噪声日志
		watchdogCheck(m, st)
		ticker := time.NewTicker(watchdogInterval)
		defer ticker.Stop()
		for range ticker.C {
			watchdogCheck(m, st)
		}
	}()
}

// watchdogState 看门狗循环状态（仅看门狗单 goroutine 访问，无需加锁）。
type watchdogState struct {
	lastStart  time.Time // 上次拉起尝试时间（冷却判定）
	failStreak int       // 连续拉起失败次数（日志限流）
	wasHealthy bool      // 上次检查 daemon 是否可达（状态转换记日志）
}

// watchdogCheck 单次检查：daemon 可达则无事（恢复时记一条日志）；
// 不可达且冷却期满则尝试拉起 Docker Desktop。
func watchdogCheck(m *Manager, st *watchdogState) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, pingErr := m.cli.Ping(ctx)
	if pingErr == nil {
		if !st.wasHealthy {
			log.Printf("[docker-watchdog] Docker daemon 已恢复可达")
			st.wasHealthy = true
		}
		st.failStreak = 0
		return // daemon 正常
	}
	if st.wasHealthy {
		log.Printf("[docker-watchdog] Docker daemon 不可达（%v）", pingErr)
		st.wasHealthy = false
	}
	if !shouldAttemptStart(time.Now(), st.lastStart) {
		return // 拉起冷却期内（daemon 启动中），下个周期复查
	}
	st.lastStart = time.Now()
	if err := StartDockerDesktop(); err != nil {
		st.failStreak++
		if shouldLogStartFailure(st.failStreak) {
			log.Printf("[docker-watchdog] 启动 Docker Desktop 失败（连续第 %d 次）: %v", st.failStreak, err)
		}
		return
	}
	st.failStreak = 0
	log.Printf("[docker-watchdog] 已拉起 Docker Desktop，进入 %s 冷却等待就绪", watchdogStartCooldown)
}

// shouldAttemptStart 判定当前是否应尝试拉起：从未拉起过，或距上次拉起 ≥ 冷却期。
// 冷却防 daemon 启动期（30-60 秒）每周期重复 spawn Docker Desktop.exe。
func shouldAttemptStart(now, lastStart time.Time) bool {
	return now.Sub(lastStart) >= watchdogStartCooldown
}

// shouldLogStartFailure 判定本次拉起失败是否记日志：第 1 次必记，其后每 N 次记一条
// （防 Docker 未安装机器随检查周期刷错误日志）。
func shouldLogStartFailure(failStreak int) bool {
	return failStreak == 1 || failStreak%watchdogFailLogEvery == 0
}
