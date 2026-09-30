// Package singleinstance - Windows 命名互斥实现（registry-image-distribution D8）。
// Global\ 命名空间跨会话唯一（RDP 多会话亦互斥）；进程退出/崩溃时 OS 自动回收句柄，
// 异常退出不残留（下次启动 ERROR_ALREADY_EXISTS 不会误判）。
//go:build windows

package singleinstance

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// mutexName 单实例互斥名（Global\ 跨会话命名空间）。
const mutexName = `Global\NexcomputeAgentSingleInstance`

var (
	kernel32      = windows.NewLazySystemDLL("kernel32.dll")
	procCreateMux = kernel32.NewProc("CreateMutexW")
)

// Acquire 尝试创建命名互斥。
// 返回 (handle, true, nil)：本进程持有（首个实例），handle 需持有至进程结束；
// 返回 (0, false, nil)：已有实例在运行（ERROR_ALREADY_EXISTS）。
func Acquire() (handle uintptr, alreadyExists bool, err error) {
	namePtr, err := syscall.UTF16PtrFromString(mutexName)
	if err != nil {
		return 0, false, fmt.Errorf("编码互斥名失败: %w", err)
	}
	ret, _, err := procCreateMux.Call(
		0,
		uintptr(0), // 初始非持有，仅创建命名对象
		uintptr(unsafe.Pointer(namePtr)),
	)
	if ret == 0 {
		return 0, false, fmt.Errorf("CreateMutex 失败: %w", err)
	}
	// ret 非 0 即句柄有效；ERROR_ALREADY_EXISTS 表示命名互斥已存在（另一实例持有）
	return ret, err == windows.ERROR_ALREADY_EXISTS, nil
}
