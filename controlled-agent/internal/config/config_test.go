// Package config - 测试（任务 14.2；agent-defaults：默认存储池根目录用例）
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
)

// setupTestConfig 重置单例并注入临时可执行路径与临时默认根目录
// （defaultStorageRoot 替换为临时目录，避免单测在真实机器上创建 D:\lab404）。
func setupTestConfig(t *testing.T) string {
	t.Helper()
	tmpDir := t.TempDir()
	cfgOnce = sync.Once{}
	cfg = nil
	origExecutable := osExecutablePath
	origDefaultRoot := defaultStorageRoot
	t.Cleanup(func() {
		osExecutablePath = origExecutable
		defaultStorageRoot = origDefaultRoot
	})
	osExecutablePath = func() (string, error) {
		return filepath.Join(tmpDir, "nexcompute-agent.exe"), nil
	}
	defaultStorageRoot = filepath.Join(tmpDir, "lab404")
	return tmpDir
}

func writeConfigFile(t *testing.T, tmpDir, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(tmpDir, "nexcompute-agent.json"), []byte(content), 0644); err != nil {
		t.Fatalf("写入配置文件失败: %v", err)
	}
}

func TestLoadOrDefault_CreatesDefault(t *testing.T) {
	tmpDir := setupTestConfig(t)

	c, err := Load()
	if err != nil {
		t.Fatalf("Load failed: %v", err)
	}
	if c.HeartbeatInterval != 1 {
		t.Errorf("expected default HeartbeatInterval=1, got %d", c.HeartbeatInterval)
	}
	if c.ConnectMode != "direct" {
		t.Errorf("expected default ConnectMode=direct, got %s", c.ConnectMode)
	}
	if c.ServerURL != "http://10.13.66.18:8080" {
		t.Errorf("expected default ServerURL, got %s", c.ServerURL)
	}
	// agent-defaults：新装即默认存储池根目录（不锁定）
	if c.StorageRoot != defaultStorageRoot {
		t.Errorf("expected default StorageRoot=%s, got %s", defaultStorageRoot, c.StorageRoot)
	}
	if c.StorageRootLocked {
		t.Error("默认存储池根目录不应锁定")
	}
	// 默认根目录应已创建
	if _, err := os.Stat(c.StorageRoot); err != nil {
		t.Errorf("默认存储池根目录未创建: %v", err)
	}

	// 配置文件应已创建
	configPath := filepath.Join(tmpDir, "nexcompute-agent.json")
	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		t.Error("config file not created")
	}
}

func TestLoadOrDefault_BackfillsEmptyStorageRoot(t *testing.T) {
	tmpDir := setupTestConfig(t)
	writeConfigFile(t, tmpDir, `{"serverUrl":"http://10.13.66.18:8080","storageRoot":""}`)

	c, err := Load()
	if err != nil {
		t.Fatalf("Load failed: %v", err)
	}
	if c.StorageRoot != defaultStorageRoot {
		t.Errorf("存量空值应补填默认值 %s, got %s", defaultStorageRoot, c.StorageRoot)
	}
	if c.StorageRootLocked {
		t.Error("补填不应锁定根目录")
	}

	// 补填结果应已落盘
	data, err := os.ReadFile(filepath.Join(tmpDir, "nexcompute-agent.json"))
	if err != nil {
		t.Fatalf("读取配置文件失败: %v", err)
	}
	var saved Config
	if err := json.Unmarshal(data, &saved); err != nil {
		t.Fatalf("解析配置文件失败: %v", err)
	}
	if saved.StorageRoot != defaultStorageRoot {
		t.Errorf("补填未持久化, 文件中 storageRoot=%s", saved.StorageRoot)
	}
}

func TestLoadOrDefault_PreservesExistingStorageRoot(t *testing.T) {
	tmpDir := setupTestConfig(t)
	existing := filepath.Join(tmpDir, "existing-root")
	writeConfigFile(t, tmpDir, `{"serverUrl":"http://10.13.66.18:8080","storageRoot":`+strconv.Quote(existing)+`}`)

	c, err := Load()
	if err != nil {
		t.Fatalf("Load failed: %v", err)
	}
	if c.StorageRoot != existing {
		t.Errorf("已有非空 storageRoot 不应被覆盖, got %s", c.StorageRoot)
	}
}

func TestUpdate_SavesConfig(t *testing.T) {
	setupTestConfig(t)

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
