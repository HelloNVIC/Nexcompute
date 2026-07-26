//go:build !windows

package executil

import (
	"fmt"
	"syscall"
)

var sysProcAttr syscall.SysProcAttr

func applyHide(attr *syscall.SysProcAttr) {}

// StartElevatedPowerShell 仅 Windows 支持（受控端为 Windows+GPU 主机部署）。
func StartElevatedPowerShell(script string) error {
	return fmt.Errorf("StartElevatedPowerShell 仅 Windows 支持")
}

// StartDetached 仅 Windows 支持。
func StartDetached(file string, args ...string) error {
	return fmt.Errorf("StartDetached 仅 Windows 支持")
}
