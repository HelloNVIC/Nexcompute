// Package gui - 启动期辅助（platform-audit-logging-ux D9）。
// ShowFatalDialog 在 Fyne 主应用创建前弹致命错误框并阻塞，供 main 启动期检查失败调用。
package gui

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/dialog"
)

// OnStorageRootChanged 根目录设置/修改后切换日志写入路径的回调（由 main 注入，4.2）。
var OnStorageRootChanged func(root string)

// ShowFatalDialog 在主应用启动前弹致命错误框，阻塞至用户确认。
// 用于启动期检查失败（如可执行文件路径含非 ASCII）拒绝启动的场景。
func ShowFatalDialog(title, message string) {
	a := app.New()
	w := a.NewWindow(title)
	w.SetFixedSize(true)
	w.Resize(fyne.NewSize(420, 200))
	dlg := dialog.NewInformation(title, message, w)
	dlg.SetOnClosed(func() { a.Quit() })
	w.SetOnClosed(func() { a.Quit() })
	dlg.Show()
	w.ShowAndRun()
}

// hasNonASCII 判断路径是否含非 ASCII 字符（D9）
func hasNonASCII(p string) bool {
	for _, r := range p {
		if r > 127 {
			return true
		}
	}
	return false
}

// notifyRootChanged 触发根目录变更回调（main 注入，切换日志写入路径，4.2）
func notifyRootChanged(root string) {
	if OnStorageRootChanged != nil {
		OnStorageRootChanged(root)
	}
}
