//go:build !windows

package executil

import "syscall"

var sysProcAttr syscall.SysProcAttr

func applyHide(attr *syscall.SysProcAttr) {}
