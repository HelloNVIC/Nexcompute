// Package agent - 进度消息与发送接口（platform-audit-logging-ux D2：OTA 5 段进度回传）。
// 受控端执行长时命令（如 agent.upgrade）过程中经此接口回传 progress 消息，
// 复用 commandId 关联 pending request，管理端 channel 识别后只更新进度不 complete future。
package agent

// ProgressMessage 进度消息（type=="progress"）。
// registry-image-distribution D5：新增 Text（分层文本，image.pull 用，如 "a1b2c3: Downloading 45%"）；
// OTA 不使用该字段，可缺省。
type ProgressMessage struct {
	Type      string `json:"type"`      // 固定 "progress"
	CommandID string `json:"commandId"` // 关联原命令
	Stage     string `json:"stage"`     // downloading/verifying/backing_up/replacing/waiting/pulling
	Percent   int    `json:"percent"`   // 0-100
	Text      string `json:"text,omitempty"` // 分层状态文本（可空，image.pull 新增）
	Timestamp int64  `json:"timestamp"`
}

// MessageSender 进度消息发送接口。
// 由 wsclient.Client 实现，executor 在长时命令中调用回传 progress，不替代最终 Result。
type MessageSender interface {
	SendProgress(msg ProgressMessage)
}

// noopSender 默认空实现（未注入 wsclient 时用）
type noopSender struct{}

func (noopSender) SendProgress(ProgressMessage) {}

// 升级阶段常量
const (
	StageDownloading = "downloading"
	StageVerifying   = "verifying"
	StageBackingUp   = "backing_up"
	StageReplacing   = "replacing"
	StageWaiting     = "waiting"
	// StagePulling 镜像拉取（registry-image-distribution D5：image.pull 进度段）
	StagePulling = "pulling"
)
