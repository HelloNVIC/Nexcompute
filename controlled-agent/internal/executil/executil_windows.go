//go:build windows

package executil

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

var sysProcAttr = syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}

func applyHide(attr *syscall.SysProcAttr) {
	attr.HideWindow = true
	if attr.CreationFlags == 0 {
		attr.CreationFlags = 0x08000000
	}
}

var (
	shell32          = syscall.NewLazyDLL("shell32.dll")
	procShellExecute = shell32.NewProc("ShellExecuteW")
)

const swShownormal = 1

// shellExecute 调用 Windows ShellExecuteW。
//   verb - 动作词，如 "runas"（UAC 提权）、"open"（默认打开）；空串表 nil
//   file - 可执行文件路径或文件名
//   args - 参数串；空串表 nil
//   cwd  - 工作目录；空串表 nil
func shellExecute(verb, file, args, cwd string) error {
	pVerb, _ := utf16Ptr(verb)
	pFile, err := utf16Ptr(file)
	if err != nil {
		return fmt.Errorf("路径无效: %w", err)
	}
	pArgs, _ := utf16Ptr(args)
	pCwd, _ := utf16Ptr(cwd)

	r1, _, _ := procShellExecute.Call(
		0,
		uintptr(unsafe.Pointer(pVerb)),
		uintptr(unsafe.Pointer(pFile)),
		uintptr(unsafe.Pointer(pArgs)),
		uintptr(unsafe.Pointer(pCwd)),
		uintptr(swShownormal),
	)
	// ShellExecute 返回值 <=32 表示错误
	if r1 <= 32 {
		return fmt.Errorf("ShellExecute 失败 (code=%d)", r1)
	}
	return nil
}

func utf16Ptr(s string) (*uint16, error) {
	if s == "" {
		return nil, nil
	}
	return syscall.UTF16PtrFromString(s)
}

// StartElevatedPowerShell 经 ShellExecute verb "runas" 拉起管理员权限的 PowerShell
// 执行 script，-NoExit 保证执行完成后窗口保持打开（platform-env-ota-realtime D3）。
// 多行脚本写临时 .ps1 后以 -NoExit -File 运行；单行脚本以 -Command 运行。
func StartElevatedPowerShell(script string) error {
	if strings.TrimSpace(script) == "" {
		return fmt.Errorf("脚本为空")
	}

	var args string
	if strings.Contains(script, "\n") {
		// 多行脚本写临时 .ps1（带 UTF-8 BOM，确保 PowerShell 5.1 正确读取非 ASCII）
		dir := os.TempDir()
		path := filepath.Join(dir, "nexcompute-envprep.ps1")
		bom := []byte{0xEF, 0xBB, 0xBF}
		if err := os.WriteFile(path, append(bom, []byte(script)...), 0644); err != nil {
			return fmt.Errorf("写临时脚本失败: %w", err)
		}
		args = fmt.Sprintf("-NoExit -NoProfile -ExecutionPolicy Bypass -File \"%s\"", path)
	} else {
		args = fmt.Sprintf("-NoExit -NoProfile -ExecutionPolicy Bypass -Command %s", script)
	}

	if err := shellExecute("runas", "powershell.exe", args, ""); err != nil {
		return fmt.Errorf("拉起管理员 PowerShell 失败: %w", err)
	}
	return nil
}

// StartDetached 以默认方式（不提权）启动一个程序，用于启动 Docker Desktop 等 GUI 程序（D6）。
func StartDetached(file string, args ...string) error {
	a := strings.Join(args, " ")
	if err := shellExecute("open", file, a, ""); err != nil {
		return fmt.Errorf("启动 %s 失败: %w", file, err)
	}
	return nil
}
