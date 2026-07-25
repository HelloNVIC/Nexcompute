// Package heartbeat 实现受控端心跳上报模块（任务 4.6）。
// 定时采集系统状态（CPU/GPU 占用与温度、内存、进程），HTTP POST 至管理端。
package heartbeat

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/docker"
	"github.com/nexcompute/controlled-agent/internal/sysinfo"
)

// Reporter 心跳上报器
type Reporter struct {
	cfg         *config.Config
	dockerMgr   *docker.Manager
	stopCh      chan struct{}
	fingerprint sysinfo.MachineFingerprint // platform-refinements #1：MAC + 机器码（注册去重用）

	// 连接状态（供 GUI 读取）
	mu           sync.RWMutex
	lastSuccess  time.Time
	lastError    string
	connected    bool
}

// ContainerStatus 本机容器运行状态（心跳回传，任务 4.1）
type ContainerStatus struct {
	ID    string `json:"id"`    // Docker 容器 ID
	Name  string `json:"name"`  // 容器名
	State string `json:"state"` // Docker 原生状态（running/exited/created/...）
}

// NewReporter 创建心跳上报器
func NewReporter(cfg *config.Config, dockerMgr *docker.Manager) *Reporter {
	return &Reporter{
		cfg:         cfg,
		dockerMgr:   dockerMgr,
		stopCh:      make(chan struct{}),
		fingerprint: sysinfo.CollectMachineFingerprint(),
	}
}

// Status 心跳状态快照
type Status struct {
	Connected      bool
	LastSuccess    time.Time
	LastError      string
	InstanceID     int64
	InstanceNumber string
	Registered     bool
}

// GetStatus 返回当前心跳状态（线程安全，供 GUI 调用）
func (r *Reporter) GetStatus() Status {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return Status{
		Connected:      r.connected,
		LastSuccess:    r.lastSuccess,
		LastError:      r.lastError,
		InstanceID:     r.cfg.InstanceID,
		InstanceNumber: r.cfg.InstanceNumber,
		Registered:     r.cfg.InstanceID != 0 && r.cfg.InstanceNumber != "",
	}
}

// Start 启动定时心跳
func (r *Reporter) Start() {
	interval := time.Duration(r.cfg.HeartbeatInterval) * time.Second
	if interval <= 0 {
		interval = 5 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	log.Println("[heartbeat] 心跳上报已启动，间隔:", interval)
	for {
		select {
		case <-ticker.C:
			r.sendHeartbeat()
		case <-r.stopCh:
			log.Println("[heartbeat] 已停止")
			return
		}
	}
}

// Stop 停止心跳
func (r *Reporter) Stop() {
	close(r.stopCh)
}

func (r *Reporter) sendHeartbeat() {
	status, err := sysinfo.Collect()
	if err != nil {
		log.Printf("[heartbeat] 采集系统状态失败: %v", err)
		return
	}

	hostInfo := sysinfo.GetHostInfo()

	// platform-improvements 任务 4.1：主机全部 IP 地址与各容器运行状态
	ipAddresses := sysinfo.CollectIPAddresses()
	containers := r.collectContainerStatuses()

	payload, _ := json.Marshal(map[string]any{
		"instanceId":     r.cfg.InstanceID,
		"instanceNumber": r.cfg.InstanceNumber,
		"agentToken":     r.cfg.AgentToken,
		"connectMode":    r.cfg.ConnectMode,
		"machineName":    hostInfo.Hostname,
		"osInfo":         hostInfo.OS,
		"agentVersion":   "0.1.0",
		"storageRoot":    r.cfg.StorageRoot,
		"status":         status,
		"ipAddresses":   ipAddresses, // 结构化主机 IP（任务 4.2 顶层字段）
		"containers":     containers, // 各容器运行状态（任务 4.2 顶层字段）
		"mac":           r.fingerprint.MAC,        // platform-refinements #1：MAC
		"machineCode":   r.fingerprint.MachineCode, // platform-refinements #1：机器码
		"timestamp":      time.Now().UnixMilli(),
	})

	resp, err := http.Post(r.cfg.HeartbeatURL, "application/json", bytes.NewReader(payload))
	if err != nil {
		log.Printf("[heartbeat] 上报失败: %v", err)
		r.mu.Lock()
		r.connected = false
		r.lastError = err.Error()
		r.mu.Unlock()
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		log.Printf("[heartbeat] 管理端返回 %d", resp.StatusCode)
		r.mu.Lock()
		r.connected = false
		r.lastError = "管理端返回 " + resp.Status
		r.mu.Unlock()
		return
	}

	// 心跳成功
	r.mu.Lock()
	r.connected = true
	r.lastSuccess = time.Now()
	r.lastError = ""
	r.mu.Unlock()

	// 解析管理端回包：首次注册取 实例 ID/编号/token；非首次取全局管理员密码补推（platform-refinements 11.2）
	var result struct {
		Code int `json:"code"`
		Data struct {
			InstanceId         int64  `json:"instanceId"`
			InstanceNumber     string `json:"instanceNumber"`
			AgentToken         string `json:"agentToken"`
			LocalAdminPassword string `json:"localAdminPassword"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err == nil && result.Code == 0 {
		if r.cfg.InstanceID == 0 {
			// 首次注册：保存实例 ID/编号/token
			_ = config.Update(func(c *config.Config) {
				c.InstanceID = result.Data.InstanceId
				if result.Data.InstanceNumber != "" {
					c.InstanceNumber = result.Data.InstanceNumber
				}
				if result.Data.AgentToken != "" {
					c.AgentToken = result.Data.AgentToken
				}
			})
			log.Printf("[heartbeat] 已注册: id=%d number=%s", result.Data.InstanceId, result.Data.InstanceNumber)
		}
		// platform-refinements 11.2：非首次心跳回包补推全局管理员密码（离线受控端上线即获取）
		if result.Data.LocalAdminPassword != "" {
			_ = config.Update(func(c *config.Config) {
				c.LocalAdminPassword = result.Data.LocalAdminPassword
			})
			log.Println("[heartbeat] 全局管理员密码已同步")
		}
	}
}

// collectContainerStatuses 采集本机各容器运行状态（任务 4.1）。
// 无 Docker 或采集失败时返回 nil（降级，不影响心跳）。
func (r *Reporter) collectContainerStatuses() []ContainerStatus {
	if r.dockerMgr == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	list, err := r.dockerMgr.ListContainers(ctx)
	if err != nil {
		log.Printf("[heartbeat] 采集容器状态失败: %v", err)
		return nil
	}
	result := make([]ContainerStatus, 0, len(list))
	for _, c := range list {
		name := ""
		if len(c.Names) > 0 {
			// Docker 容器名带前导 '/'
			name = strings.TrimPrefix(c.Names[0], "/")
		}
		result = append(result, ContainerStatus{
			ID:    c.ID,
			Name:  name,
			State: c.State,
		})
	}
	return result
}
