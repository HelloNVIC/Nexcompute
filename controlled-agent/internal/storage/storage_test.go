// Package storage - 测试（任务 14.2，platform-refinements 11.3：明文密码）
package storage

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/nexcompute/controlled-agent/internal/config"
)

func setupTestConfig(t *testing.T) (*config.Config, func()) {
	t.Helper()
	tmpDir := t.TempDir()
	cfg := &config.Config{
		StorageRoot:        tmpDir,
		StorageRootLocked:  true,
		LocalAdminPassword: "admin123",
	}
	return cfg, func() { os.RemoveAll(tmpDir) }
}

func TestCreatePoolDir(t *testing.T) {
	cfg, cleanup := setupTestConfig(t)
	defer cleanup()

	mgr := NewManager(cfg)
	path, err := mgr.CreatePoolDir("01", "2021001A", "bert-finetune")
	if err != nil {
		t.Fatalf("CreatePoolDir failed: %v", err)
	}

	expected := filepath.Join(cfg.StorageRoot, "01-2021001A-bert-finetune")
	if path != expected {
		t.Errorf("expected path %s, got %s", expected, path)
	}

	if _, err := os.Stat(path); os.IsNotExist(err) {
		t.Error("pool directory not created")
	}
}

func TestSetRoot_WhenUnlocked_Succeeds(t *testing.T) {
	tmpDir := t.TempDir()
	defer os.RemoveAll(tmpDir)

	cfg := &config.Config{StorageRoot: "", StorageRootLocked: false}
	// 使用 mock config update
	mgr := NewManager(cfg)

	newRoot := filepath.Join(tmpDir, "newroot")
	err := mgr.SetRoot(newRoot)
	if err != nil {
		t.Fatalf("SetRoot failed: %v", err)
	}
}

func TestSetRoot_WhenLocked_Fails(t *testing.T) {
	cfg := &config.Config{StorageRoot: "/existing", StorageRootLocked: true}
	mgr := NewManager(cfg)

	err := mgr.SetRoot("/new")
	if err == nil {
		t.Error("expected error when setting locked root, got nil")
	}
}

func TestChangeRoot_WrongPassword_Fails(t *testing.T) {
	cfg := &config.Config{
		StorageRoot:        "/existing",
		StorageRootLocked:  true,
		LocalAdminPassword: "correct",
	}
	mgr := NewManager(cfg)

	err := mgr.ChangeRoot("/new", "wrong")
	if err == nil {
		t.Error("expected error with wrong password")
	}
}

func TestChangeRoot_CorrectPassword_Succeeds(t *testing.T) {
	tmpDir := t.TempDir()
	defer os.RemoveAll(tmpDir)

	cfg := &config.Config{
		StorageRoot:        filepath.Join(tmpDir, "old"),
		StorageRootLocked:  true,
		LocalAdminPassword: "correct",
	}
	mgr := NewManager(cfg)

	newRoot := filepath.Join(tmpDir, "new")
	err := mgr.ChangeRoot(newRoot, "correct")
	if err != nil {
		t.Fatalf("ChangeRoot failed: %v", err)
	}
}

func TestCreatePoolDir_NoRoot_Fails(t *testing.T) {
	cfg := &config.Config{StorageRoot: ""}
	mgr := NewManager(cfg)

	_, err := mgr.CreatePoolDir("01", "2021001A", "project")
	if err == nil {
		t.Error("expected error when storage root not set")
	}
}

func TestVerifyPassword(t *testing.T) {
	stored := "secret"
	if !verifyPassword(stored, "secret") {
		t.Error("expected verifyPassword to return true for correct password")
	}
	if verifyPassword(stored, "wrong") {
		t.Error("expected verifyPassword to return false for wrong password")
	}
	if verifyPassword("", "anything") {
		t.Error("expected verifyPassword to return false for empty stored password")
	}
}
