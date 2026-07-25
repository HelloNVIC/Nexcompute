// Package power 实现防睡眠休眠（任务 5.8）。
// Windows 下通过 SetThreadExecutionState 阻止系统进入睡眠/休眠，
// 受控端退出时恢复默认电源管理行为。
package power

import (
	"log"
	"runtime"
)

// Manager 电源管理器
type Manager struct {
	active bool
}

// NewManager 创建电源管理器
func NewManager() *Manager {
	return &Manager{}
}

// PreventSleep 阻止系统睡眠/休眠
func (m *Manager) PreventSleep() {
	if runtime.GOOS != "windows" {
		log.Println("[power] 非 Windows 平台，跳过防睡眠")
		return
	}
	if err := setThreadExecutionState(); err != nil {
		log.Printf("[power] 设置防睡眠失败: %v", err)
		return
	}
	m.active = true
	log.Println("[power] 已阻止系统睡眠/休眠")
}

// Restore 恢复默认电源管理行为
func (m *Manager) Restore() {
	if !m.active {
		return
	}
	if runtime.GOOS != "windows" {
		return
	}
	if err := clearThreadExecutionState(); err != nil {
		log.Printf("[power] 恢复电源管理失败: %v", err)
		return
	}
	log.Println("[power] 已恢复默认电源管理")
}
