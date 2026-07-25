// Package autostart 实现开机自启动（任务 5.3）。
// Windows 下通过注册表 HKCU\Software\Microsoft\Windows\CurrentVersion\Run 注册启动项。
package autostart

import (
	"os"
	"path/filepath"
)

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`
const appName = "NexcomputeAgent"

// Enable 开启开机自启
func Enable() error {
	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	return setRunValue(appName, exePath)
}

// Disable 关闭开机自启
func Disable() error {
	return deleteRunValue(appName)
}

// IsEnabled 检查是否已开启自启
func IsEnabled() bool {
	val, err := getRunValue(appName)
	if err != nil || val == "" {
		return false
	}
	exePath, _ := os.Executable()
	return filepath.Clean(val) == filepath.Clean(exePath)
}
