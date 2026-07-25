// Package docker - 环境检查（任务 5.7）
// 等价于: docker info | grep "runtime\|nvidia" ; nvidia-smi -L
package docker

import (
	"context"
	"fmt"
	"os/exec"
	"strings"

	"github.com/docker/docker/api/types/system"
	"github.com/nexcompute/controlled-agent/internal/executil"
)

// CheckEnvironmentFull 完整 Docker 环境检查
// 检查：Docker Desktop 运行、nvidia 运行时、GPU 可访问性
func (m *Manager) CheckEnvironmentFull(ctx context.Context) (*EnvironmentCheck, error) {
	check := &EnvironmentCheck{}

	// 1. Docker daemon 可用性（等价 docker info "Server Version"）
	info, err := m.cli.Info(ctx)
	if err != nil {
		check.DockerAvailable = false
		check.Error = "Docker Desktop 未运行或 API 不可用: " + err.Error()
		return check, nil
	}
	check.DockerAvailable = true
	check.DockerVersion = info.ServerVersion

	// 2. nvidia 运行时检测（等价 docker info | grep nvidia）
	_, hasNvidiaRuntime := info.Runtimes["nvidia"]
	if !hasNvidiaRuntime {
		check.Error = fmt.Sprintf("Docker 未配置 nvidia 运行时（当前运行时: %s，可用: %s）",
			info.DefaultRuntime, runtimeNames(info.Runtimes))
		check.GPUAvailable = false
		return check, nil
	}
	check.ToolkitVersion = "nvidia runtime (default=" + info.DefaultRuntime + ")"

	// 3. GPU 可访问性（等价 nvidia-smi -L）
	gpuList, gpuOK := detectGPU(ctx)
	if !gpuOK {
		check.GPUAvailable = false
		check.Error = "nvidia-smi 不可用，未检测到 GPU"
		return check, nil
	}
	check.GPUAvailable = true
	if check.Error == "" {
		check.Error = "检测到 " + gpuList
	}

	return check, nil
}

// detectGPU 等价 nvidia-smi -L，返回 GPU 列表与是否可用
func detectGPU(ctx context.Context) (string, bool) {
	cmd := executil.HideWindow(exec.CommandContext(ctx, "nvidia-smi", "-L"))
	out, err := cmd.Output()
	if err != nil || len(out) == 0 {
		return "", false
	}
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	if len(lines) == 0 {
		return "", false
	}
	// 提取第一行的 GPU 名（如 "GPU 0: NVIDIA GeForce RTX 4090 D (UUID: ...)"）
	first := strings.TrimSpace(lines[0])
	return first, true
}

// runtimeNames 提取运行时名称列表
func runtimeNames(runtimes map[string]system.RuntimeWithStatus) string {
	names := make([]string, 0, len(runtimes))
	for name := range runtimes {
		names = append(names, name)
	}
	return strings.Join(names, ", ")
}
