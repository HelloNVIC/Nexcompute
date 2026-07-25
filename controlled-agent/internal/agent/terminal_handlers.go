// Package agent - 在线容器终端（platform-refinements #2）
// 交互式 docker exec（Tty），后台 goroutine 读 stdout 入缓冲，前端轮询 read + write stdin。
package agent

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

// terminalSession 一个交互式 exec 会话
type terminalSession struct {
	conn    net.Conn
	reader  io.Reader
	closeFn func() error
	buf     []byte
	mu      sync.Mutex
	done    chan struct{}
}

// terminalManager 管理所有终端会话（单进程内）
type terminalManager struct {
	sessions map[string]*terminalSession
	mu       sync.Mutex
}

var termMgr = &terminalManager{sessions: make(map[string]*terminalSession)}

// handleTerminalOpen 开启交互式终端（platform-refinements #2）
func (e *Executor) handleTerminalOpen(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}
	containerID, _ := cmd.Payload["containerId"].(string)
	shell, _ := cmd.Payload["shell"].(string)
	if shell == "" {
		shell = "bash"
	}
	if containerID == "" {
		return "", fmt.Errorf("containerId 为空")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	resp, err := e.docker.ExecAttachInteractive(ctx, containerID, []string{shell})
	if err != nil {
		// bash 不存在则回退 sh
		if shell == "bash" {
			resp, err = e.docker.ExecAttachInteractive(ctx, containerID, []string{"sh"})
			if err != nil {
				return "", fmt.Errorf("打开终端失败: %w", err)
			}
		} else {
			return "", fmt.Errorf("打开终端失败: %w", err)
		}
	}
	sessionID := fmt.Sprintf("term-%d", time.Now().UnixNano())
	s := &terminalSession{
		conn:   resp.Conn,
		reader: resp.Reader,
		closeFn: func() error {
			resp.Close()
			return nil
		},
		done: make(chan struct{}),
	}
	termMgr.mu.Lock()
	termMgr.sessions[sessionID] = s
	termMgr.mu.Unlock()
	// 后台读取 stdout 入缓冲
	go func() {
		buf := make([]byte, 8192)
		for {
			n, err := s.reader.Read(buf)
			if n > 0 {
				s.mu.Lock()
				s.buf = append(s.buf, buf[:n]...)
				s.mu.Unlock()
			}
			if err != nil {
				break
			}
		}
		close(s.done)
		log.Printf("[terminal] 会话 %s 读循环结束", sessionID)
	}()
	log.Printf("[terminal] 会话已开启: %s", sessionID)
	return fmt.Sprintf(`{"sessionId":"%s"}`, sessionID), nil
}

// handleTerminalWrite 写入 stdin（platform-refinements #2，data 为 base64）
func (e *Executor) handleTerminalWrite(cmd *Command) (string, error) {
	sessionID, _ := cmd.Payload["sessionId"].(string)
	dataB64, _ := cmd.Payload["data"].(string)
	s := termMgr.get(sessionID)
	if s == nil {
		return "", fmt.Errorf("会话不存在: %s", sessionID)
	}
	data, err := base64.StdEncoding.DecodeString(dataB64)
	if err != nil {
		return "", fmt.Errorf("base64 解码失败: %w", err)
	}
	if _, err := s.conn.Write(data); err != nil {
		return "", fmt.Errorf("写入失败: %w", err)
	}
	return "ok", nil
}

// handleTerminalRead 读取缓冲的 stdout（platform-refinements #2，返回 base64，读后清空）
func (e *Executor) handleTerminalRead(cmd *Command) (string, error) {
	sessionID, _ := cmd.Payload["sessionId"].(string)
	s := termMgr.get(sessionID)
	if s == nil {
		return "", fmt.Errorf("会话不存在: %s", sessionID)
	}
	s.mu.Lock()
	out := s.buf
	s.buf = nil
	s.mu.Unlock()
	if len(out) == 0 {
		return `{"data":""}`, nil
	}
	return fmt.Sprintf(`{"data":"%s"}`, base64.StdEncoding.EncodeToString(out)), nil
}

// handleTerminalClose 关闭终端会话（platform-refinements #2）
func (e *Executor) handleTerminalClose(cmd *Command) (string, error) {
	sessionID, _ := cmd.Payload["sessionId"].(string)
	s := termMgr.remove(sessionID)
	if s != nil {
		_ = s.closeFn()
	}
	return "closed", nil
}

func (m *terminalManager) get(id string) *terminalSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessions[id]
}

func (m *terminalManager) remove(id string) *terminalSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.sessions[id]
	delete(m.sessions, id)
	return s
}
