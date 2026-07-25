//go:build windows

package executil

import "syscall"

var sysProcAttr = syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}

func applyHide(attr *syscall.SysProcAttr) {
	attr.HideWindow = true
	if attr.CreationFlags == 0 {
		attr.CreationFlags = 0x08000000
	}
}
