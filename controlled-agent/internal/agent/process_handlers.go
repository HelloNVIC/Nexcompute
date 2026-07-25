// Package agent - 进程列表采集（任务 11.6）
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/shirou/gopsutil/v4/process"
)

// handleProcessList 进程列表查询（任务 11.6、11.5）
// scope: host（宿主机全量）/ containers（指定容器内进程）
func (e *Executor) handleProcessList(cmd *Command) (string, error) {
	scope, _ := cmd.Payload["scope"].(string)
	switch scope {
	case "host":
		return e.collectHostProcesses()
	case "containers":
		containerIds, _ := cmd.Payload["containerIds"].([]any)
		return e.collectContainerProcesses(containerIds)
	default:
		// 未指定 scope 时默认宿主机全量（兼容前端缺省调用）
		return e.collectHostProcesses()
	}
}

// collectHostProcesses 宿主机全量进程（管理员）
// 限制返回数量避免 WS 消息过大（close 1009）
func (e *Executor) collectHostProcesses() (string, error) {
	// 采集进程列表可能较慢，用较长超时
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	procs, err := process.ProcessesWithContext(ctx)
	if err != nil {
		return "", fmt.Errorf("获取进程列表失败: %w", err)
	}

	// 按 CPU 占用降序排序，只返回前 50 个（避免 WS 消息过大 close 1009）
	const maxReturn = 50
	const maxNameLen = 40
	type procInfo struct {
		pid  int32
		name string
		cpu  float64
		mem  float64
	}
	infos := make([]procInfo, 0, len(procs))
	for _, p := range procs {
		name, _ := p.Name()
		if len(name) > maxNameLen {
			name = name[:maxNameLen]
		}
		cpu, _ := p.CPUPercent()
		mem, _ := p.MemoryPercent()
		infos = append(infos, procInfo{p.Pid, name, cpu, float64(mem)})
	}
	// 按 CPU 降序取前 maxReturn
	for i := 0; i < len(infos) && i < maxReturn; i++ {
		maxIdx := i
		for j := i + 1; j < len(infos); j++ {
			if infos[j].cpu > infos[maxIdx].cpu {
				maxIdx = j
			}
		}
		infos[i], infos[maxIdx] = infos[maxIdx], infos[i]
	}
	returnCount := len(infos)
	if returnCount > maxReturn {
		returnCount = maxReturn
	}

	result := make([]map[string]any, 0, returnCount)
	for i := 0; i < returnCount; i++ {
		result = append(result, map[string]any{
			"pid":    infos[i].pid,
			"name":   infos[i].name,
			"cpu":    infos[i].cpu,
			"memory": infos[i].mem,
		})
	}

	data, _ := json.Marshal(map[string]any{
		"scope":     "host",
		"total":     len(procs),
		"returned":  returnCount,
		"processes": result,
	})
	log.Printf("[process] 宿主机进程采集: 共 %d 个，返回前 %d", len(procs), returnCount)
	return string(data), nil
}

// collectContainerProcesses 容器内进程（学生/导师）
func (e *Executor) collectContainerProcesses(containerIds []any) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	result := make(map[string]any)
	result["scope"] = "containers"
	containers := make([]map[string]any, 0)

	for _, cid := range containerIds {
		containerID, _ := cid.(string)
		if containerID == "" {
			continue
		}

		// 通过 docker exec 在容器内运行 ps
		output, err := e.docker.ExecInContainer(ctx, containerID,
			[]string{"ps", "aux", "--no-headers"})
		if err != nil {
			log.Printf("[process] 容器 %s 进程采集失败: %v", containerID, err)
			continue
		}
		containers = append(containers, map[string]any{
			"containerId": containerID,
			"output":      output,
		})
	}
	result["containers"] = containers

	data, _ := json.Marshal(result)
	return string(data), nil
}

// 抑制未使用导入
var _ = types.Container{}
