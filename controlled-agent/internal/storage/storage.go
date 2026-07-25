// Package storage 实现存储池目录管理（任务 5.9、8.3）。
// 存储池根目录设置（设后不可改，修改需本地管理员密码）。
// 在根目录下按命名（物理机编号-工号/学号-项目名）创建文件夹。
package storage

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/nexcompute/controlled-agent/internal/config"
)

// Manager 存储池管理器
type Manager struct {
	cfg *config.Config
}

// NewManager 创建存储管理器
func NewManager(cfg *config.Config) *Manager {
	return &Manager{cfg: cfg}
}

// SetRoot 设置存储池根目录（首次设置，设后不可改）
func (m *Manager) SetRoot(path string) error {
	if m.cfg.StorageRootLocked && m.cfg.StorageRoot != "" {
		return errors.New("存储池根目录已设置且锁定，修改需本地管理员密码")
	}
	if err := os.MkdirAll(path, 0755); err != nil {
		return err
	}
	// 更新本地 cfg（指针，直接生效）
	m.cfg.StorageRoot = path
	m.cfg.StorageRootLocked = true
	// 持久化到全局配置（若已加载）
	m.persist()
	return nil
}

// ChangeRoot 修改存储池根目录（需本地管理员密码，platform-refinements 11.3：明文校验）
func (m *Manager) ChangeRoot(path, adminPassword string) error {
	if !verifyPassword(m.cfg.LocalAdminPassword, adminPassword) {
		return errors.New("本地管理员密码错误")
	}
	if err := os.MkdirAll(path, 0755); err != nil {
		return err
	}
	m.cfg.StorageRoot = path
	m.persist()
	return nil
}

// CreatePoolDir 在根目录下创建存储池文件夹（任务 8.3）
// 命名格式：物理机编号-工号/学号-项目名
func (m *Manager) CreatePoolDir(instanceNumber, userId, projectName string) (string, error) {
	if m.cfg.StorageRoot == "" {
		return "", errors.New("存储池根目录未设置")
	}
	poolName := instanceNumber + "-" + userId + "-" + projectName
	poolPath := filepath.Join(m.cfg.StorageRoot, poolName)
	if err := os.MkdirAll(poolPath, 0755); err != nil {
		return "", err
	}
	return poolPath, nil
}

// DeletePoolDir 删除存储池文件夹（platform-refinements 7.2）。
// 安全：poolPath 必须解析为 StorageRoot 的子路径，避免路径穿越删除其他目录。
func (m *Manager) DeletePoolDir(poolPath string) error {
	if m.cfg.StorageRoot == "" {
		return errors.New("存储池根目录未设置")
	}
	abs, err := filepath.Abs(poolPath)
	if err != nil {
		return err
	}
	rootAbs, err := filepath.Abs(m.cfg.StorageRoot)
	if err != nil {
		return err
	}
	rel, err := filepath.Rel(rootAbs, abs)
	if err != nil || rel == "." || strings.HasPrefix(rel, "..") || strings.HasPrefix(rel, ".") {
		return errors.New("路径不在存储池根目录下，拒绝删除")
	}
	return os.RemoveAll(abs)
}

// persist 将全局配置持久化到磁盘（若全局配置已加载）
func (m *Manager) persist() {
	if !config.IsLoaded() {
		return // 全局配置未加载（测试环境），跳过持久化
	}
	_ = config.Update(func(c *config.Config) {
		c.StorageRoot = m.cfg.StorageRoot
		c.StorageRootLocked = m.cfg.StorageRootLocked
	})
}

// CopyDir 递归复制 src 目录到 dst（platform-refinements #3：存储根目录迁移）
func CopyDir(src, dst string) error {
	info, err := os.Stat(src)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return errors.New("源不是目录")
	}
	if err := os.MkdirAll(dst, 0755); err != nil {
		return err
	}
	return filepath.Walk(src, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, path)
		target := filepath.Join(dst, rel)
		if fi.IsDir() {
			return os.MkdirAll(target, fi.Mode())
		}
		if !fi.Mode().IsRegular() {
			return nil // 跳过符号链接等非常规文件
		}
		return copyFile(path, target, fi.Mode())
	})
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// verifyPassword 校验本地管理员密码（platform-refinements 11.3：明文比对）。
func verifyPassword(stored, input string) bool {
	if stored == "" {
		return false
	}
	return stored == input
}
