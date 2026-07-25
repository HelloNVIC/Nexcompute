// Package agent - 测试（任务 14.2）
package agent

import (
	"testing"

	"github.com/nexcompute/controlled-agent/internal/config"
)

func TestNewExecutor(t *testing.T) {
	cfg := &config.Config{AgentToken: "test-token"}
	e := NewExecutor(cfg)
	if e == nil {
		t.Fatal("expected non-nil executor")
	}
	if e.cfg != cfg {
		t.Error("cfg not set correctly")
	}
}

func TestValidateSource_ValidToken(t *testing.T) {
	cfg := &config.Config{AgentToken: "secret-token"}
	e := NewExecutor(cfg)

	cmd := &Command{
		ID:    "cmd-1",
		Type:  "system.restart",
		Token: "secret-token",
	}

	if !e.ValidateSource(cmd) {
		t.Error("expected ValidateSource to return true for valid token")
	}
}

func TestValidateSource_InvalidToken(t *testing.T) {
	cfg := &config.Config{AgentToken: "secret-token"}
	e := NewExecutor(cfg)

	cmd := &Command{
		ID:    "cmd-1",
		Type:  "system.restart",
		Token: "wrong-token",
	}

	if e.ValidateSource(cmd) {
		t.Error("expected ValidateSource to return false for invalid token")
	}
}

func TestValidateSource_EmptyToken(t *testing.T) {
	cfg := &config.Config{AgentToken: "secret-token"}
	e := NewExecutor(cfg)

	cmd := &Command{
		ID:    "cmd-1",
		Type:  "system.restart",
		Token: "",
	}

	if e.ValidateSource(cmd) {
		t.Error("expected ValidateSource to return false for empty token")
	}
}

func TestExecute_UnknownCommand(t *testing.T) {
	cfg := &config.Config{AgentToken: "token"}
	e := NewExecutor(cfg)

	cmd := &Command{
		ID:    "cmd-1",
		Type:  "unknown.command",
		Token: "token",
	}

	result := e.Execute(cmd)
	if result.Success {
		t.Error("expected failure for unknown command")
	}
	if result.CommandID != "cmd-1" {
		t.Errorf("expected command ID cmd-1, got %s", result.CommandID)
	}
	if result.Error == "" {
		t.Error("expected non-empty error message")
	}
}

func TestExecute_ValidatesSourceFirst(t *testing.T) {
	cfg := &config.Config{AgentToken: "token"}
	e := NewExecutor(cfg)

	cmd := &Command{
		ID:    "cmd-1",
		Type:  "system.restart",
		Token: "wrong", // invalid token
	}

	result := e.Execute(cmd)
	if result.Success {
		t.Error("expected failure for unauthenticated command")
	}
}

func TestCommand_Structure(t *testing.T) {
	cmd := Command{
		ID:    "test-id",
		Type:  "container.create",
		Token: "token",
		Payload: map[string]any{
			"imageRef": "pytorch:latest",
			"cpuLimit": 2.0,
		},
		Timestamp: 1234567890,
	}

	if cmd.ID != "test-id" {
		t.Error("ID mismatch")
	}
	if cmd.Type != "container.create" {
		t.Error("Type mismatch")
	}
	if cmd.Payload["imageRef"] != "pytorch:latest" {
		t.Error("Payload mismatch")
	}
}

func TestResult_Structure(t *testing.T) {
	r := Result{
		CommandID: "cmd-1",
		Success:   true,
		Output:    "ok",
		Timestamp: 1234567890,
	}

	if !r.Success {
		t.Error("expected success")
	}
	if r.Output != "ok" {
		t.Error("output mismatch")
	}
}
