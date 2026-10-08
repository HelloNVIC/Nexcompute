// Package logging 实现受控端日志落盘（platform-audit-logging-ux D8）。
// rotating writer 落 <storageRoot>/log/agent-YYYY-MM-DD.log，跨日切文件；
// 根目录未设时回退 %LOCALAPPDATA%/Nexcompute/log/；保留 30 天自动清理。
// 实现 io.Writer，由 main 经 log.SetOutput 注入替换标准 log 默认输出。
package logging

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

const (
	// 日志保留期：30 天
	retentionDays = 30
	// 文件名前缀
	filePrefix = "agent-"
	// 文件名日期格式
	dateFormat = "2006-01-02"
	// 文件扩展
	fileExt = ".log"
)

// Writer 按日分割的日志 writer。
// 当前写入文件路径随日期切换；根目录未设时回退 LOCALAPPDATA。
type Writer struct {
	mu       sync.Mutex
	rootDir  string // 当前 log 目录（不含 log 子目录本身）
	logDir   string // rootDir + "/log"
	current  *os.File
	curDate  string // YYYY-MM-DD
	fallback bool   // 是否回退到 LOCALAPPDATA
}

// New 创建 writer。storageRoot 为空时回退 LOCALAPPDATA。
func New(storageRoot string) *Writer {
	w := &Writer{}
	w.setRoot(storageRoot)
	return w
}

// SetRoot 切换根目录（用户设置/修改根目录后调用，D8：根目录设置后切换写入路径）。
func (w *Writer) SetRoot(storageRoot string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.setRoot(storageRoot)
	// 关闭旧文件，下次 Write 重新按新路径打开
	if w.current != nil {
		_ = w.current.Close()
		w.current = nil
		w.curDate = ""
	}
}

func (w *Writer) setRoot(storageRoot string) {
	if storageRoot != "" {
		w.rootDir = storageRoot
		w.logDir = filepath.Join(storageRoot, "log")
		w.fallback = false
		return
	}
	// 回退 %LOCALAPPDATA%/Nexcompute/log/
	w.rootDir = fallbackDir()
	w.logDir = filepath.Join(w.rootDir, "log")
	w.fallback = true
}

func fallbackDir() string {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		// 非 Windows 回退到用户家目录
		if home, err := os.UserHomeDir(); err == nil {
			base = home
		} else {
			base = "."
		}
	}
	return filepath.Join(base, "Nexcompute")
}

// LogDir 返回当前日志目录（agent.log 命令按此列文件）。
func (w *Writer) LogDir() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.logDir
}

// Write 实现 io.Writer：按当前日期写对应文件，跨日切文件。
func (w *Writer) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	today := time.Now().Format(dateFormat)
	if w.current == nil || w.curDate != today {
		err := w.rotateLocked(today)
		if err != nil && !w.fallback {
			// agent-defaults：根目录不可用（如默认 D:\lab404 所在盘缺失/不可写）时回退
			// %LOCALAPPDATA% 重试。注意此处不得用 log.Printf——会经 MultiWriter 重入 Write 死锁。
			fmt.Fprintf(os.Stderr, "[logging] 日志目录 %s 不可用（%v），回退 LOCALAPPDATA\n", w.logDir, err)
			w.setRoot("")
			err = w.rotateLocked(today)
		}
		if err != nil {
			// 写文件失败兜底写 stderr，避免日志丢失致进程异常
			fmt.Fprintf(os.Stderr, "[logging] 打开日志文件失败: %v\n", err)
			return os.Stderr.Write(p)
		}
	}
	n, err := w.current.Write(p)
	if err != nil {
		// 写失败尝试重开一次
		_ = w.rotateLocked(today)
		return n, err
	}
	return n, nil
}

func (w *Writer) rotateLocked(today string) error {
	if w.current != nil {
		_ = w.current.Close()
		w.current = nil
	}
	if err := os.MkdirAll(w.logDir, 0755); err != nil {
		return err
	}
	path := filepath.Join(w.logDir, filePrefix+today+fileExt)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	w.current = f
	w.curDate = today
	return nil
}

// Close 关闭当前文件
func (w *Writer) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.current != nil {
		err := w.current.Close()
		w.current = nil
		return err
	}
	return nil
}

// Cleanup 扫描日志目录，删除 mtime 早于 30 天的日志文件（D8：保留 30 天）。
func (w *Writer) Cleanup() {
	w.mu.Lock()
	dir := w.logDir
	w.mu.Unlock()
	cleanupDir(dir)
}

func cleanupDir(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cutoff := time.Now().AddDate(0, 0, -retentionDays)
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !isLogFile(name) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			if err := os.Remove(filepath.Join(dir, name)); err == nil {
				log.Printf("[logging] 清理过期日志: %s (mtime=%s)", name, info.ModTime().Format(dateFormat))
			}
		}
	}
}

func isLogFile(name string) bool {
	return len(name) > len(filePrefix)+len(fileExt) &&
		name[:len(filePrefix)] == filePrefix &&
		filepath.Ext(name) == fileExt
}

// StartCleanupLoop 启动定时清理（启动后立即清一次，之后每小时扫一次）。
// 返回 stop 闭包用于停止。
func (w *Writer) StartCleanupLoop() (stop func()) {
	ticker := time.NewTicker(1 * time.Hour)
	done := make(chan struct{})
	go func() {
		w.Cleanup() // 启动即清
		for {
			select {
			case <-done:
				ticker.Stop()
				return
			case <-ticker.C:
				w.Cleanup()
			}
		}
	}()
	return func() { close(done) }
}

// EnsureWriter 将 w 设为标准 log 的输出（main 注入，task 4.4）。
func (w *Writer) EnsureWriter() {
	log.SetOutput(io.MultiWriter(w, os.Stderr))
}

// no-op to keep runtime import on non-windows (LOCALAPPDATA may be empty there)
var _ = runtime.GOOS
