// Package agent - 非 Windows 平台 stub（任务 6.5）

//go:build !windows

package agent

import "fmt"

func turnOffScreen() (string, error) {
	return "", fmt.Errorf("息屏仅在 Windows 上支持")
}
