// Package config - 测试（任务 14.2）
package config

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestLoadOrDefault_CreatesDefault(t *testing.T) {
	// 重置单例
	cfgOnce = sync.Once{}
	cfg = nil

	// 临时目录作为可执行路径
	tmpDir := t.TempDir()
	origExecutable := osExecutablePath
	t.Cleanup(func() { osExecutablePath = origExecutable })
	osExecutablePath = func() (string, error) {
		return filepath.Join(tmpDir, "nexcompute-agent.exe"), nil
	}

	c, err := Load()
	if err != nil {
		t.Fatalf("Load failed: %v", err)
	}
	if c.HeartbeatInterval != 5 {
		t.Errorf("expected default HeartbeatInterval=5, got %d", c.HeartbeatInterval)
	}
	if c.ConnectMode != "direct" {
		t.Errorf("expected default ConnectMode=direct, got %s", c.ConnectMode)
	}
	if c.ServerURL != "http://localhost:8080" {
		t.Errorf("expected default ServerURL, got %s", c.ServerURL)
	}

	// 配置文件应已创建
	configPath := filepath.Join(tmpDir, "nexcompute-agent.json")
	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		t.Error("config file not created")
	}
}

func TestUpdate_SavesConfig(t *testing.T) {
	cfgOnce = sync.Once{}
	cfg = nil
	tmpDir := t.TempDir()
	origExecutable := osExecutablePath
	t.Cleanup(func() { osExecutablePath = origExecutable })
	osExecutablePath = func() (string, error) {
		return filepath.Join(tmpDir, "nexcompute-agent.exe"), nil
	}

	_, _ = Load()
	err := Update(func(c *Config) {
		c.InstanceNumber = "01"
		c.StorageRoot = "/test/root"
	})
	if err != nil {
		t.Fatalf("Update failed: %v", err)
	}

	// 重新加载验证
	cfgOnce = sync.Once{}
	cfg = nil
	reloaded, _ := Load()
	if reloaded.InstanceNumber != "01" {
		t.Errorf("expected InstanceNumber=01, got %s", reloaded.InstanceNumber)
	}
	if reloaded.StorageRoot != "/test/root" {
		t.Errorf("expected StorageRoot=/test/root, got %s", reloaded.StorageRoot)
	}
}

func TestWsURLFromHTTP(t *testing.T) {
	cases := []struct {
		input, expected string
	}{
		{"http://localhost:8080", "ws://localhost:8080"},
		{"https://example.com", "wss://example.com"},
		{"other://test", "other://test"},
	}
	for _, tc := range cases {
		got := wsURLFromHTTP(tc.input)
		if got != tc.expected {
			t.Errorf("wsURLFromHTTP(%s) = %s, want %s", tc.input, got, tc.expected)
		}
	}
}
