// Package agent - 进度消息与发送接口（platform-audit-logging-ux D2：OTA 5 段进度回传）。
// 受控端执行长时命令（如 agent.upgrade）过程中经此接口回传 progress 消息，
// 复用 commandId 关联 pending request，管理端 channel 识别后只更新进度不 complete future。
package agent

// ProgressMessage 升级进度消息（type=="progress"）。
type ProgressMessage struct {
	Type      string `json:"type"`      // 固定 "progress"
	CommandID string `json:"commandId"` // 关联原命令
	Stage     string `json:"stage"`     // downloading/verifying/backing_up/replacing/waiting
	Percent   int    `json:"percent"`   // 0-100
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
)
