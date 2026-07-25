// Package agent - Windows 息屏实现（任务 6.5）

//go:build windows

package agent

import (
	"fmt"
	"syscall"
	"unsafe"
)

const (
	wmSyscommand    = 0x0112
	scMonitorpower  = 0xF170
	hwndBroadcast   = 0xFFFF
)

var (
	user32              = syscall.NewLazyDLL("user32.dll")
	procSendMessage     = user32.NewProc("SendMessageW")
	procPostMessage     = user32.NewProc("PostMessageW")
)

func turnOffScreen() (string, error) {
	// 通过 PostMessage 广播 SC_MONITORPOWER 关闭显示器
	// -1 打开, 2 关闭, 1 节能
	ret, _, err := procPostMessage.Call(
		uintptr(hwndBroadcast),
		uintptr(wmSyscommand),
		uintptr(scMonitorpower),
		uintptr(2),
	)
	if ret == 0 && err != nil {
		// PostMessage 返回 0 可能并非错误，但记录
		return "", fmt.Errorf("PostMessage 失败: %w", err)
	}
	return "息屏指令已发送", nil
}

// suppress unused
var _ = unsafe.Sizeof(0)
var _ = procSendMessage
