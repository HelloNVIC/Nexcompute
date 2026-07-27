// Package agent - 受控端日志查看命令处理器（platform-audit-logging-ux D8）。
// 处理 agent.log 命令：
//   - list 模式：列 log/ 目录文件（文件名/大小/修改时间）
//   - tail 模式：读指定日期日志文件尾 N 行（默认 500，避免大文件整文件回传）
package agent

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// defaultTailLines 默认尾行数（D8 R6：不做整文件下载，仅尾 N 行）
const defaultTailLines = 500

// maxTailLines 上限，防恶意请求回传超大内容
const maxTailLines = 5000

// handleAgentLog 处理 agent.log 命令
func (e *Executor) handleAgentLog(cmd *Command) (string, error) {
	mode, _ := cmd.Payload["mode"].(string)
	if mode == "" {
		mode = "list"
	}
	logDir := e.logDirFn()
	if logDir == "" {
		return "", fmt.Errorf("日志目录未配置")
	}

	switch mode {
	case "list":
		return listLogFiles(logDir)
	case "tail":
		date, _ := cmd.Payload["date"].(string)
		if date == "" {
			// 默认今日
			date = todayStr()
		}
		tailLines := defaultTailLines
		if n, ok := intField(cmd.Payload["tailLines"]); ok && n > 0 {
			tailLines = n
			if tailLines > maxTailLines {
				tailLines = maxTailLines
			}
		}
		return tailLogFile(logDir, date, tailLines)
	default:
		return "", fmt.Errorf("未知 mode: %s", mode)
	}
}

// logDirFn 返回日志目录。默认从配置 storageRoot 拼出；main 注入 logging writer 后可覆盖。
// 实现：优先用注入的 logDir 路径（由 logging.Writer.LogDir() 设置），回退 StorageRoot/log。
var logDirOverride string

func (e *Executor) logDirFn() string {
	if logDirOverride != "" {
		return logDirOverride
	}
	if e.cfg.StorageRoot != "" {
		return filepath.Join(e.cfg.StorageRoot, "log")
	}
	return ""
}

// SetLogDir 由 main 注入日志目录（来自 logging.Writer.LogDir，含 LOCALAPPDATA 回退）
func SetLogDir(dir string) {
	logDirOverride = dir
}

func listLogFiles(dir string) (string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			// 目录不存在视为空列表（受控端首次运行尚未落日志）
			body, _ := json.Marshal(map[string]any{"files": []any{}})
			return string(body), nil
		}
		return "", fmt.Errorf("读取日志目录失败: %w", err)
	}
	type f struct {
		Name    string `json:"name"`
		Size    int64  `json:"size"`
		ModTime string `json:"modTime"`
	}
	out := make([]f, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if !strings.HasPrefix(e.Name(), "agent-") || !strings.HasSuffix(e.Name(), ".log") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, f{
			Name:    e.Name(),
			Size:    info.Size(),
			ModTime: info.ModTime().Format("2006-01-02 15:04:05"),
		})
	}
	body, _ := json.Marshal(map[string]any{"files": out})
	return string(body), nil
}

func tailLogFile(dir, date string, tailLines int) (string, error) {
	// 文件名：agent-YYYY-MM-DD.log
	name := "agent-" + date + ".log"
	if !looksLikeDate(date) {
		return "", fmt.Errorf("非法日期参数")
	}
	path := filepath.Join(dir, name)
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return "", fmt.Errorf("日期 %s 的日志文件不存在", date)
		}
		return "", fmt.Errorf("打开日志失败: %w", err)
	}
	defer f.Close()

	lines, err := readTail(f, tailLines)
	if err != nil {
		return "", fmt.Errorf("读取尾行失败: %w", err)
	}
	body, _ := json.Marshal(map[string]any{
		"date":     date,
		"file":     name,
		"tailLines": tailLines,
		"content":  strings.Join(lines, "\n"),
	})
	return string(body), nil
}

// readTail 读文件尾 n 行（环形缓冲，避免大文件全读入内存）
func readTail(f *os.File, n int) ([]string, error) {
	if n <= 0 {
		n = defaultTailLines
	}
	buf := make([]string, 0, n)
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		buf = append(buf, scanner.Text())
		if len(buf) > n {
			buf = buf[len(buf)-n:]
		}
	}
	if err := scanner.Err(); err != nil {
		return buf, err
	}
	return buf, nil
}

func looksLikeDate(s string) bool {
	if len(s) != 10 {
		return false
	}
	for i, c := range s {
		switch i {
		case 4, 7:
			if c != '-' {
				return false
			}
		default:
			if c < '0' || c > '9' {
				return false
			}
		}
	}
	return true
}

func intField(v any) (int, bool) {
	switch n := v.(type) {
	case float64:
		return int(n), true
	case int:
		return n, true
	case int64:
		return int(n), true
	}
	return 0, false
}

func todayStr() string {
	return time.Now().Format("2006-01-02")
}
