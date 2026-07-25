// Package power - Windows 实现 SetThreadExecutionState（任务 5.8）。
// 非 Windows 平台使用 stub。

//go:build windows

package power

import "syscall"

const (
	esContinuous      = 0x80000000
	esSystemRequired  = 0x00000001
	esDisplayRequired = 0x00000002 // 防息屏（息屏命令需要时可单独控制）
)

var (
	kernel32                   = syscall.NewLazyDLL("kernel32.dll")
	procSetThreadExecutionState = kernel32.NewProc("SetThreadExecutionState")
)

func setThreadExecutionState() error {
	r1, _, err := procSetThreadExecutionState.Call(uintptr(esContinuous | esSystemRequired))
	if r1 == 0 {
		return err
	}
	return nil
}

func clearThreadExecutionState() error {
	r1, _, err := procSetThreadExecutionState.Call(uintptr(esContinuous))
	if r1 == 0 {
		return err
	}
	return nil
}
