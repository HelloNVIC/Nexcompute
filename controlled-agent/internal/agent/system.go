// Package agent - 系统控制命令处理器（任务 6.5）
package agent

import (
	"context"
	"fmt"
	"os/exec"
	"runtime"
	"time"

	"github.com/nexcompute/controlled-agent/internal/executil"
)

// handleRestart 重启系统
func (e *Executor) handleRestart(cmd *Command) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	switch runtime.GOOS {
	case "windows":
		// shutdown /r /t 0：立即重启
		out, err := executil.HideWindow(exec.CommandContext(ctx, "shutdown", "/r", "/t", "5")).CombinedOutput()
		if err != nil {
			return "", fmt.Errorf("重启失败: %w, output: %s", err, string(out))
		}
		return "重启指令已发送（5 秒后重启）", nil
	default:
		return "", fmt.Errorf("不支持的平台: %s", runtime.GOOS)
	}
}

// handleScreenOff 息屏
func (e *Executor) handleScreenOff(cmd *Command) (string, error) {
	if runtime.GOOS != "windows" {
		return "", fmt.Errorf("不支持的平台: %s", runtime.GOOS)
	}
	return turnOffScreen()
}

// handlePowerShell 执行 PowerShell 命令
func (e *Executor) handlePowerShell(cmd *Command) (string, error) {
	command, _ := cmd.Payload["command"].(string)
	if command == "" {
		return "", fmt.Errorf("命令为空")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	out, err := executil.HideWindow(exec.CommandContext(ctx, "powershell", "-NoProfile", "-NonInteractive", "-Command", command)).CombinedOutput()
	output := string(out)
	if err != nil {
		return output, fmt.Errorf("PowerShell 执行失败: %w", err)
	}
	return output, nil
}
