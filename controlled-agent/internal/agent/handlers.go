// Package agent - 命令处理器占位实现。
// 各 handler 在对应任务中补全实现。
// system.* / storage.* / image.* / container.* 已在各自文件实现。
// 仅 processList / fileTransfer 为占位。
package agent

import (
	"errors"
	"fmt"
	"log"

	"github.com/nexcompute/controlled-agent/internal/config"
)

func errUnknownCommand(t string) error {
	return fmt.Errorf("未知命令类型: %s", t)
}

// handleSetAdminPassword 接收管理端下发的全局本地管理员密码并明文保存（platform-refinements 11.3）。
// 命令类型 config.set_admin_password，payload {password}（明文）。
func (e *Executor) handleSetAdminPassword(cmd *Command) (string, error) {
	password, _ := cmd.Payload["password"].(string)
	if password == "" {
		return "", errors.New("password 为空")
	}
	err := config.Update(func(c *config.Config) {
		c.LocalAdminPassword = password
	})
	if err != nil {
		return "", fmt.Errorf("保存密码失败: %w", err)
	}
	log.Println("[agent] 全局本地管理员密码已更新")
	return "ok", nil
}

func (e *Executor) handleFileTransfer(cmd *Command) (string, error) {
	return "", errors.New("待实现：任务 7.4/7.5")
}