// Package security 实现命令来源校验（任务 4.8）。
// 仅执行经鉴权的管理端命令，拒绝并记录未鉴权命令。
package security

import (
	"log"
	"sync"
	"time"
)

// AuditRecord 安全审计记录（未鉴权命令拒绝日志）
type AuditRecord struct {
	Time      time.Time `json:"time"`
	CommandID string    `json:"commandId"`
	Type      string    `json:"type"`
	Reason    string    `json:"reason"`
}

// Auditor 安全审计器
type Auditor struct {
	mu      sync.Mutex
	records []AuditRecord
}

// NewAuditor 创建审计器
func NewAuditor() *Auditor {
	return &Auditor{}
}

// LogRejection 记录被拒绝的未鉴权命令
func (a *Auditor) LogRejection(cmdID, cmdType, reason string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	rec := AuditRecord{
		Time:      time.Now(),
		CommandID: cmdID,
		Type:      cmdType,
		Reason:    reason,
	}
	a.records = append(a.records, rec)
	log.Printf("[security] 拒绝未鉴权命令: type=%s id=%s reason=%s", cmdType, cmdID, reason)
}

// Records 返回审计记录副本
func (a *Auditor) Records() []AuditRecord {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]AuditRecord, len(a.records))
	copy(out, a.records)
	return out
}
