// Package gui - 系统托盘常驻（任务 5.1）
package gui

import (
	"github.com/getlantern/systray"
)

// Tray 系统托盘
type Tray struct {
	onShow func()
	onQuit func()
}

// NewTray 创建系统托盘
func NewTray(onShow, onQuit func()) *Tray {
	return &Tray{onShow: onShow, onQuit: onQuit}
}

// Run 启动系统托盘（阻塞）
func (t *Tray) Run() {
	systray.Run(t.onReady, t.onExit)
}

func (t *Tray) onReady() {
	systray.SetIcon(TrayIconBytes())
	systray.SetTitle("合算")
	systray.SetTooltip("Nexcompute 受控端")

	mShow := systray.AddMenuItem("显示主窗口", "显示受控端主窗口")
	systray.AddSeparator()
	mQuit := systray.AddMenuItem("退出", "退出受控端（需密码）")

	go func() {
		for {
			select {
			case <-mShow.ClickedCh:
				if t.onShow != nil {
					t.onShow()
				}
			case <-mQuit.ClickedCh:
				if t.onQuit != nil {
					t.onQuit()
				}
			}
		}
	}()
}

func (t *Tray) onExit() {
	// 托盘退出时的清理
}

// Quit 退出系统托盘
func (t *Tray) Quit() {
	systray.Quit()
}
