// Package executil 提供 exec 命令的跨平台封装，隐藏 Windows 控制台窗口。
package executil

import (
	"os/exec"
)

// HideWindow 为 exec.Cmd 设置隐藏控制台窗口属性。
// Windows 上 exec 默认会弹出一个 cmd 窗口一闪而过，
// 设置 CREATE_NO_WINDOW (0x08000000) 避免弹窗（心跳采集 nvidia-smi 等）。
func HideWindow(cmd *exec.Cmd) *exec.Cmd {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &sysProcAttr
	} else {
		applyHide(cmd.SysProcAttr)
	}
	return cmd
}
