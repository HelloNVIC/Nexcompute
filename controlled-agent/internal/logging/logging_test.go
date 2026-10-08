// Package logging - 根目录不可用回退测试（agent-defaults：默认根目录所在盘缺失场景）
package logging

import (
	"os"
	"path/filepath"
	"testing"
)

// TestWrite_FallsBackWhenRootUnusable 根目录不可写（路径组件为普通文件）时，
// 日志回退 LOCALAPPDATA 目录，Write 不报错、进程不受影响。
func TestWrite_FallsBackWhenRootUnusable(t *testing.T) {
	// 以一个普通文件充当日志根目录 → MkdirAll(root/log) 必然失败
	blocker := filepath.Join(t.TempDir(), "blocker")
	if err := os.WriteFile(blocker, []byte("x"), 0644); err != nil {
		t.Fatalf("创建占位文件失败: %v", err)
	}
	w := New(blocker)
	defer w.Close()
	if _, err := w.Write([]byte("test line\n")); err != nil {
		t.Fatalf("Write 不应报错: %v", err)
	}
	if !w.fallback {
		t.Error("根目录不可用时应回退 fallback 目录")
	}
	if w.current == nil {
		t.Error("回退后应已打开日志文件")
	}
}
