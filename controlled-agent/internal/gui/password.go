// Package gui - 退出与受保护配置的密码保护（任务 5.5，platform-refinements 11.3/11.4）
// 全局本地管理员密码明文校验（密码由管理端统一下发）。
package gui

import (
	"errors"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"github.com/nexcompute/controlled-agent/internal/config"
)

// VerifyExitPassword 弹出密码输入框，校验本地管理员密码后才允许退出。
// 返回 true 表示密码正确（允许退出）。未设置密码时直接允许（首次下发前）。
func VerifyExitPassword(window fyne.Window, onSuccess func()) {
	cfg := config.Get()
	if cfg.LocalAdminPassword == "" {
		// 未设置密码时直接允许（首次启动未下发密码前）
		onSuccess()
		return
	}

	entry := widget.NewPasswordEntry()
	dialog.ShowCustomConfirm("退出受控端", "确认退出", "取消",
		container.NewVBox(
			widget.NewLabel("请输入本地管理员密码以退出受控端："),
			entry,
		),
		func(confirm bool) {
			if !confirm {
				return
			}
			if entry.Text == cfg.LocalAdminPassword {
				onSuccess()
			} else {
				dialog.ShowError(errors.New("密码错误，无法退出"), window)
			}
		}, window)
}

// VerifyPasswordForRootChange 修改存储池根目录时的密码校验（platform-refinements 11.4）。
// 校验通过后将用户输入的密码传给 onSuccess（供 ChangeRoot 二次校验，defense-in-depth）。
func VerifyPasswordForRootChange(window fyne.Window, onSuccess func(password string)) {
	cfg := config.Get()
	if cfg.LocalAdminPassword == "" {
		onSuccess("")
		return
	}

	entry := widget.NewPasswordEntry()
	dialog.ShowCustomConfirm("修改存储池根目录", "确认", "取消",
		container.NewVBox(
			widget.NewLabel("修改存储池根目录需要本地管理员密码："),
			entry,
		),
		func(confirm bool) {
			if !confirm {
				return
			}
			if entry.Text == cfg.LocalAdminPassword {
				onSuccess(entry.Text)
			} else {
				dialog.ShowError(errors.New("密码错误"), window)
			}
		}, window)
}
