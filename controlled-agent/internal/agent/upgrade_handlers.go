// Package agent - 受控端 OTA 自更新处理器（platform-env-ota-realtime D7）。
// 流程：下载新版 exe -> MD5 校验 -> 备份当前 exe -> 写 updater.bat ->
// cmd /c start updater.bat -> 优雅退出；updater.bat 替换并重启，失败回滚 .bak。
package agent

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/filetransfer"
)

// handleUpgrade 处理 agent.upgrade 命令。
// payload {version, md5, downloadUrl}：downloadUrl 为管理端 exe 源路径（file-transfer sourcePath）。
func (e *Executor) handleUpgrade(cmd *Command) (string, error) {
	version, _ := cmd.Payload["version"].(string)
	expectedMD5, _ := cmd.Payload["md5"].(string)
	downloadURL, _ := cmd.Payload["downloadUrl"].(string)
	if downloadURL == "" || expectedMD5 == "" {
		return "", fmt.Errorf("缺少 downloadUrl 或 md5")
	}
	log.Printf("[upgrade] 收到升级命令: version=%s md5=%s", version, expectedMD5)

	// 1. 下载新版 exe 至当前 exe 同目录（须同卷，跨卷 move/ren 会失败）
	cfg := config.Get()
	currentExePath, err := os.Executable()
	if err != nil {
		return "", fmt.Errorf("获取当前 exe 路径失败: %w", err)
	}
	exeDir := filepath.Dir(currentExePath)
	newPath := filepath.Join(exeDir, "nexcompute-agent.new.exe")
	transferID := fmt.Sprintf("ota-%s-%d", randomHex(8), time.Now().UnixNano())
	downloader := filetransfer.NewDownloader(cfg.ServerURL, cfg.AgentToken, cfg.InstanceNumber, 0)
	if err := downloader.Download(downloadURL, newPath, transferID, "agent-upgrade", nil); err != nil {
		return "", fmt.Errorf("下载新版 exe 失败: %w", err)
	}

	// 2. MD5 校验（应用层，与命令携带的 md5 比对；传输层已做 SHA-256）
	actualMD5, err := md5File(newPath)
	if err != nil {
		os.Remove(newPath)
		return "", fmt.Errorf("计算下载文件 MD5 失败: %w", err)
	}
	if !strings.EqualFold(actualMD5, expectedMD5) {
		os.Remove(newPath)
		log.Printf("[upgrade] MD5 校验失败: 期望 %s 实际 %s，不替换", expectedMD5, actualMD5)
		return "", fmt.Errorf("MD5 校验失败: 期望 %s 实际 %s", expectedMD5, actualMD5)
	}
	log.Println("[upgrade] MD5 校验通过")

	// 3. 备份当前 exe（运行中 exe 可读不可写，复制到 .bak）
	currentExe := currentExePath
	backupPath := filepath.Join(exeDir, filepath.Base(currentExe)+".bak")
	if err := copyFile(currentExe, backupPath); err != nil {
		os.Remove(newPath)
		return "", fmt.Errorf("备份当前 exe 失败: %w", err)
	}
	log.Printf("[upgrade] 已备份当前 exe -> %s", backupPath)

	// 4. 写 updater.bat（ren 当前 exe -> .old -> move new -> start；失败回滚 .bak）
	updaterPath := filepath.Join(exeDir, "updater.bat")
	bat := buildUpdaterBat(newPath, currentExe, backupPath)
	if err := os.WriteFile(updaterPath, []byte(bat), 0644); err != nil {
		os.Remove(newPath)
		return "", fmt.Errorf("写 updater.bat 失败: %w", err)
	}

	// 5. 拉起 updater（独立进程），随即安排优雅退出
	launchUpdater(updaterPath, exeDir)

	// 延迟 1 秒退出，确保 WS 结果先回传管理端
	if e.quitFunc != nil {
		go func() {
			time.Sleep(1 * time.Second)
			log.Println("[upgrade] 优雅退出受控端，由 updater 完成替换与重启")
			e.quitFunc()
		}()
	} else {
		log.Println("[upgrade] 未注入 quitFunc，无法自动退出；请手动重启受控端以完成替换")
	}

	return fmt.Sprintf(`{"version":"%s","md5":"%s","status":"replacing"}`, version, actualMD5), nil
}

// buildUpdaterBat 生成 updater.bat 内容。
//
// Windows 下不能覆盖运行中的 exe（文件锁），但可以**重命名**运行中的 exe。
// 故替换策略（重试循环等待进程退出释放句柄）：
//  1. ren 当前 exe -> *.old（运行中可重命名，不可覆盖）
//  2. move new exe -> 当前 exe 名
//  3. start 新版 exe
//  4. del *.old（清理旧版；失败无所谓，下次启动也会清理）
//
// 每步带重试（最多 ~30s），进程退出释放锁后即可成功；
// 全部失败则回滚 .bak 并启动旧版，避免受控端失联。
//
// 不在 bat 内自删（del %~f0 会致"找不到批处理文件/内存资源不足"），
// 改由新版受控端启动时清理 updater.bat（见 main.cleanupUpgradeLeftovers）。
func buildUpdaterBat(newExe, currentExe, backupPath string) string {
	currentName := filepath.Base(currentExe)
	// CRLF 行尾 + 纯 ASCII 注释：cmd.exe 按 ANSI 代码页解析 .bat，
	// LF-only 会导致行被截断、中文注释字节错位破坏 goto 标签解析。
	lines := []string{
		"@echo off",
		"timeout /t 2 /nobreak >nul",
		`set "NEW={{NEW}}"`,
		`set "CUR={{CUR}}"`,
		`set "OLD={{OLD}}"`,
		`set "BAK={{BAK}}"`,
		"rem 1) rename running exe to .old (can rename while running; retry until process exits)",
		"set /a tries=0",
		":ren_loop",
		`if exist "%OLD%" del /f /q "%OLD%" >nul 2>&1`,
		`ren "%CUR%" "{{OLDNAME}}.old" >nul 2>&1 && goto moved`,
		"set /a tries+=1",
		`if %tries% lss 30 (timeout /t 1 /nobreak >nul & goto ren_loop)`,
		"goto rollback",
		":moved",
		"rem 2) move new exe into place",
		"set /a tries=0",
		":move_loop",
		`move /Y "%NEW%" "%CUR%" >nul 2>&1 && goto start_new`,
		"set /a tries+=1",
		`if %tries% lss 10 (timeout /t 1 /nobreak >nul & goto move_loop)`,
		"rem move failed: restore .old then rollback",
		`move /Y "%OLD%" "%CUR%" >nul 2>&1`,
		"goto rollback",
		":start_new",
		`start "" "%CUR%"`,
		`del /f /q "%OLD%" >nul 2>&1`,
		"exit /b 0",
		":rollback",
		`move /Y "%BAK%" "%CUR%" >nul 2>&1`,
		`start "" "%CUR%"`,
		"exit /b 1",
		"",
	}
	bat := strings.Join(lines, "\r\n")
	r := strings.NewReplacer(
		"{{NEW}}", newExe,
		"{{CUR}}", currentExe,
		"{{OLD}}", currentExe+".old",
		"{{BAK}}", backupPath,
		"{{OLDNAME}}", currentName,
	)
	return r.Replace(bat)
}

// launchUpdater 经 cmd /c start 拉起 updater.bat（独立进程，不阻塞）。
func launchUpdater(updaterPath, workDir string) {
	cmd := exec.Command("cmd", "/c", "start", "", filepath.Base(updaterPath))
	cmd.Dir = workDir
	if err := cmd.Start(); err != nil {
		log.Printf("[upgrade] 拉起 updater 失败: %v", err)
		return
	}
	log.Printf("[upgrade] updater.bat 已启动，2 秒后替换并重启")
}

// copyFile 复用 storage_handlers.go 中的 copyFile（备份当前 exe 用）
