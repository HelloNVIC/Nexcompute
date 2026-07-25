// Package wsclient 实现受控端 WebSocket 命令通道客户端（任务 4.7）。
// 受控端主动连出，接收管理端派发的命令并回传结果。
// 支持重连退避、命令接收与执行、结果回传。
package wsclient

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/nexcompute/controlled-agent/internal/agent"
	"github.com/nexcompute/controlled-agent/internal/config"
)

// Client WebSocket 客户端
type Client struct {
	cfg      *config.Config
	executor *agent.Executor
	conn     *websocket.Conn
	stopCh   chan struct{}

	// 并发写锁（多 goroutine 回传结果时保护 conn）
	writeMu sync.Mutex
}

// NewClient 创建 WS 客户端
func NewClient(cfg *config.Config, executor *agent.Executor) *Client {
	return &Client{
		cfg:      cfg,
		executor: executor,
		stopCh:   make(chan struct{}),
	}
}

// Start 启动 WS 客户端（含重连退避）
// 首次连接前等待心跳注册完成（InstanceNumber 非空）
func (c *Client) Start() {
	// 等待心跳注册完成（实例编号已分配）
	for {
		if c.cfg.InstanceNumber != "" && c.cfg.AgentToken != "" {
			break
		}
		select {
		case <-c.stopCh:
			return
		default:
		}
		time.Sleep(2 * time.Second)
	}

	for {
		select {
		case <-c.stopCh:
			return
		default:
		}
		if err := c.connectAndRun(); err != nil {
			log.Printf("[ws] 连接失败: %v", err)
		}
		c.backoffSleep()
	}
}

// Stop 停止客户端
func (c *Client) Stop() {
	close(c.stopCh)
	if c.conn != nil {
		_ = c.conn.Close()
	}
}

func (c *Client) connectAndRun() error {
	headers := http.Header{}
	headers.Set("X-Agent-Token", c.cfg.AgentToken)
	headers.Set("X-Instance-Number", c.cfg.InstanceNumber)

	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.Dial(c.cfg.WSURL, headers)
	if err != nil {
		return err
	}
	c.conn = conn
	log.Println("[ws] 连接已建立")

	defer conn.Close()

	for {
		select {
		case <-c.stopCh:
			return nil
		default:
		}
		_, msg, err := conn.ReadMessage()
		if err != nil {
			log.Printf("[ws] 读取消息失败: %v", err)
			return err
		}
		go c.handleMessage(msg)
	}
}

func (c *Client) handleMessage(data []byte) {
	var cmd agent.Command
	if err := json.Unmarshal(data, &cmd); err != nil {
		log.Printf("[ws] 解析命令失败: %v", err)
		return
	}

	// 命令来源校验（任务 4.8）
	if !c.executor.ValidateSource(&cmd) {
		log.Printf("[ws] 拒绝未鉴权命令: %s", cmd.Type)
		return
	}

	result := c.executor.Execute(&cmd)
	resultBytes, _ := json.Marshal(result)
	c.writeMu.Lock()
	if c.conn == nil {
		log.Printf("[ws] 回传失败：连接已关闭: %s", cmd.ID)
		c.writeMu.Unlock()
		return
	}
	err := c.conn.WriteMessage(websocket.TextMessage, resultBytes)
	c.writeMu.Unlock()
	if err != nil {
		log.Printf("[ws] 回传结果失败: %v", err)
	}
}

func (c *Client) backoffSleep() {
	base := time.Duration(c.cfg.WSReconnectBaseMs) * time.Millisecond
	if base <= 0 {
		base = 2 * time.Second
	}
	max := time.Duration(c.cfg.WSReconnectMaxMs) * time.Millisecond
	if max <= 0 {
		max = 60 * time.Second
	}
	// 简单指数退避，上限 max
	delay := base
	if delay > max {
		delay = max
	}
	time.Sleep(delay)
}
