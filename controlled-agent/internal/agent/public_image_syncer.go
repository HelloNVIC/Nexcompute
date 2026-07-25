// Package agent - 公共镜像定时同步调度（任务 9.8）
package agent

import (
	"log"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
)

// PublicImageSyncer 公共镜像定时同步器
type PublicImageSyncer struct {
	cfg      *config.Config
	executor *Executor
	stopCh   chan struct{}
}

// NewPublicImageSyncer 创建同步器
func NewPublicImageSyncer(cfg *config.Config, executor *Executor) *PublicImageSyncer {
	return &PublicImageSyncer{
		cfg:      cfg,
		executor: executor,
		stopCh:   make(chan struct{}),
	}
}

// Start 启动定时同步（启动时一次 + 每隔固定时间）
func (s *PublicImageSyncer) Start() {
	interval := time.Duration(s.cfg.PublicImageSyncInterval) * time.Second
	if interval <= 0 {
		interval = 1 * time.Hour
	}

	// 启动时同步一次
	go s.syncOnce()

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	log.Printf("[image-sync] 定时同步已启动，间隔: %v", interval)
	for {
		select {
		case <-ticker.C:
			s.syncOnce()
		case <-s.stopCh:
			log.Println("[image-sync] 已停止")
			return
		}
	}
}

// Stop 停止同步
func (s *PublicImageSyncer) Stop() {
	close(s.stopCh)
}

func (s *PublicImageSyncer) syncOnce() {
	result, err := s.executor.handleImageSyncPublic(&Command{Type: "image.sync_public"})
	if err != nil {
		log.Printf("[image-sync] 同步失败: %v", err)
		return
	}
	log.Printf("[image-sync] 同步完成: %s", result)
}
