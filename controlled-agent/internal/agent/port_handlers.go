// Package agent - 端口查询处理器（platform-refinements #6）
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"time"
)

// handleQueryUsedPorts 返回本机 docker 当前已绑定的宿主端口集合（platform-refinements #6）
// 供管理端分配端口时避开实际占用（含系统外创建的容器或泄漏未释放的端口）。
func (e *Executor) handleQueryUsedPorts(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	list, err := e.docker.ListContainers(ctx)
	if err != nil {
		return "", fmt.Errorf("列出容器失败: %w", err)
	}
	set := map[int]struct{}{}
	for _, c := range list {
		for _, p := range c.Ports {
			if p.PublicPort > 0 {
				set[int(p.PublicPort)] = struct{}{}
			}
		}
	}
	out := make([]int, 0, len(set))
	for port := range set {
		out = append(out, port)
	}
	sort.Ints(out)
	body, _ := json.Marshal(map[string]any{"ports": out})
	return string(body), nil
}
