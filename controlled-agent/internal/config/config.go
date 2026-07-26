// Package config 管理受控端本地配置（持久化到 JSON 文件）。
// 配置项：控制端 IP、心跳间隔、WS 重连策略、存储池根目录、本地管理员密码等。
package config

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"sync"
)

// Config 受控端本地配置
type Config struct {
	// 控制端（管理端）地址
	ServerURL    string `json:"serverUrl"`
	HeartbeatURL string `json:"heartbeatUrl"`
	WSURL        string `json:"wsUrl"`

	// 心跳间隔（秒）
	HeartbeatInterval int `json:"heartbeatInterval"`

	// WS 重连策略
	WSReconnectBaseMs int `json:"wsReconnectBaseMs"`
	WSReconnectMaxMs  int `json:"wsReconnectMaxMs"`

	// 连接模式：direct / tunnel（穿透，敬请期待）
	ConnectMode string `json:"connectMode"`

	// 物理实例编号（首次注册后由管理端分配并回传）
	InstanceNumber string `json:"instanceNumber"`
	InstanceID     int64  `json:"instanceId"`

	// 鉴权凭证
	AgentToken string `json:"agentToken"`

	// 存储池根目录（设后不可改，修改需本地管理员密码）
	StorageRoot string `json:"storageRoot"`
	StorageRootLocked bool `json:"storageRootLocked"`

	// 本地管理员密码（platform-refinements 11.3：全局共享、明文保存；管理端统一下发）
	LocalAdminPassword string `json:"localAdminPassword"`

	// 公共镜像同步间隔（秒）
	PublicImageSyncInterval int `json:"publicImageSyncInterval"`

	// platform-refinements #2：开机自启默认开启；用户经管理密码关闭后置 true，不再自动拉起
	AutostartDisabled bool `json:"autostartDisabled"`
}

var (
	defaultCfg = Config{
		ServerURL:              "http://localhost:8080",
		HeartbeatInterval:      1, // platform-refinements #4：心跳 1 秒一次
		WSReconnectBaseMs:      2000,
		WSReconnectMaxMs:       60000,
		ConnectMode:            "direct",
		PublicImageSyncInterval: 3600,
	}
	cfg     *Config
	cfgOnce sync.Once
	cfgMu   sync.RWMutex
)

// withDerivedURLs 根据 ServerURL 派生心跳与 WS 地址
func withDerivedURLs(c *Config) *Config {
	if c.ServerURL != "" {
		c.HeartbeatURL = c.ServerURL + "/api/agent/heartbeat"
		c.WSURL = wsURLFromHTTP(c.ServerURL) + "/api/agent/ws"
	}
	return c
}

// Load 加载配置，若不存在则使用默认值并创建配置文件
func Load() (*Config, error) {
	cfgOnce.Do(func() {
		cfg = loadOrDefault()
	})
	return cfg, nil
}

// Get 获取当前配置（须先 Load）
func Get() *Config {
	cfgMu.RLock()
	defer cfgMu.RUnlock()
	return cfg
}

// Save 持久化配置到磁盘
func Save() error {
	cfgMu.RLock()
	defer cfgMu.RUnlock()
	return persist(cfg)
}

// Update 以回调方式原子更新配置并持久化
func Update(fn func(c *Config)) error {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	if cfg == nil {
		return errors.New("config not loaded")
	}
	fn(cfg)
	// 确保 HeartbeatURL/WSURL 始终与 ServerURL 一致
	withDerivedURLs(cfg)
	return persist(cfg)
}

// IsLoaded 返回全局配置是否已加载
func IsLoaded() bool {
	cfgMu.RLock()
	defer cfgMu.RUnlock()
	return cfg != nil
}

func loadOrDefault() *Config {
	path := configPath()
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			c := defaultCfg
			c = *withDerivedURLs(&c)
			if err := persist(&c); err != nil {
				log.Printf("[config] 创建默认配置失败: %v", err)
			}
			return &c
		}
		log.Printf("[config] 读取配置失败: %v，使用默认值", err)
		c := defaultCfg
		return withDerivedURLs(&c)
	}
	c := defaultCfg
	if err := json.Unmarshal(data, &c); err != nil {
		log.Printf("[config] 解析配置失败: %v，使用默认值", err)
	}
	return withDerivedURLs(&c)
}

func persist(c *Config) error {
	path := configPath()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}

func configPath() string {
	exe, err := osExecutablePath()
	if err != nil {
		return "nexcompute-agent.json"
	}
	return filepath.Join(filepath.Dir(exe), "nexcompute-agent.json")
}

// osExecutablePath 可替换的 os.Executable（便于测试）
var osExecutablePath = os.Executable

// EnvDir 受控端本地 Env 文件夹（存储池根目录下 Env 子目录，D4）。
// 环境准备文件经 MD5 增量同步至此。
func (c *Config) EnvDir() string {
	return filepath.Join(c.StorageRoot, "Env")
}

func wsURLFromHTTP(httpURL string) string {
	if len(httpURL) > 4 && httpURL[:5] == "https" {
		return "wss" + httpURL[5:]
	}
	if len(httpURL) > 3 && httpURL[:4] == "http" {
		return "ws" + httpURL[4:]
	}
	return httpURL
}
