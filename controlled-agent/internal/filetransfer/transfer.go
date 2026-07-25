// Package filetransfer 实现可断点续传的大文件传输（任务 7.4、7.5）。
// 被 存储池迁移、镜像 tar 分发、公共镜像同步 三场景复用。
// 分块传输、断点续传、进度上报、完整性校验。
package filetransfer

// TransferConfig 传输配置
type TransferConfig struct {
	ChunkSize   int64  `json:"chunkSize"`   // 分块大小（字节）
	ChecksumAlgo string `json:"checksumAlgo"` // sha256
}

// TransferProgress 传输进度
type TransferProgress struct {
	TransferID   string `json:"transferId"`
	Direction    string `json:"direction"` // upload / download
	TotalChunks  int    `json:"totalChunks"`
	DoneChunks   int    `json:"doneChunks"`
	TotalBytes   int64  `json:"totalBytes"`
	DoneBytes    int64  `json:"doneBytes"`
	Status       string `json:"status"` // pending / transferring / completed / failed
	Checksum     string `json:"checksum,omitempty"`
}

// Chunk 文件块
type Chunk struct {
	Index    int    `json:"index"`
	Data     []byte `json:"data"`
	Checksum string `json:"checksum"`
}

// Manager 文件传输管理器
type Manager struct {
	config TransferConfig
}

// NewManager 创建传输管理器
func NewManager(cfg TransferConfig) *Manager {
	if cfg.ChunkSize <= 0 {
		cfg.ChunkSize = 4 * 1024 * 1024 // 4MB
	}
	if cfg.ChecksumAlgo == "" {
		cfg.ChecksumAlgo = "sha256"
	}
	return &Manager{config: cfg}
}

// Config 返回传输配置
func (m *Manager) Config() TransferConfig {
	return m.config
}
