// Package docker 封装本地 Docker API 调用（任务 5.10）。
// 容器、镜像、卷操作。Docker daemon 不开放远程 API，所有操作在本地执行。
package docker

import (
	"context"
	"path/filepath"
	"strings"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
)

// Manager Docker 操作封装
type Manager struct {
	cli *client.Client
}

// NewManager 创建 Docker 管理器
func NewManager() (*Manager, error) {
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, err
	}
	return &Manager{cli: cli}, nil
}

// CheckEnvironment 检查 Docker 环境可用性（任务 5.7）
func (m *Manager) CheckEnvironment(ctx context.Context) (*EnvironmentCheck, error) {
	check := &EnvironmentCheck{}

	ping, err := m.cli.Ping(ctx)
	if err != nil {
		check.DockerAvailable = false
		check.Error = err.Error()
		return check, nil
	}
	check.DockerAvailable = true
	check.DockerVersion = ping.APIVersion

	// GPU/Toolkit 检测在任务 5.7 中补全
	return check, nil
}

// ListContainers 列出容器
func (m *Manager) ListContainers(ctx context.Context) ([]types.Container, error) {
	return m.cli.ContainerList(ctx, container.ListOptions{All: true})
}

// ContainersUsingRoot 返回挂载源位于 rootPath 下的容器名列表（platform-refinements #3：变更存储根目录前置检查）
func (m *Manager) ContainersUsingRoot(ctx context.Context, rootPath string) []string {
	if rootPath == "" {
		return nil
	}
	rootAbs, _ := filepath.Abs(rootPath)
	list, err := m.cli.ContainerList(ctx, container.ListOptions{All: true})
	if err != nil {
		return nil
	}
	var names []string
	seen := map[string]bool{}
	for _, c := range list {
		for _, mp := range c.Mounts {
			if mp.Source == "" {
				continue
			}
			srcAbs, _ := filepath.Abs(mp.Source)
			if strings.HasPrefix(srcAbs, rootAbs+string(filepath.Separator)) {
				for _, n := range c.Names {
					if !seen[n] {
						seen[n] = true
						names = append(names, n)
					}
				}
				break
			}
		}
	}
	return names
}

// EnvironmentCheck Docker 环境检查结果
type EnvironmentCheck struct {
	DockerAvailable bool   `json:"dockerAvailable"`
	DockerVersion   string `json:"dockerVersion"`
	GPUAvailable    bool   `json:"gpuAvailable"`
	ToolkitVersion   string `json:"toolkitVersion,omitempty"`
	Error           string `json:"error,omitempty"`
}
