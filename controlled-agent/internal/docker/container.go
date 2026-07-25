// Package docker - 容器操作封装（任务 5.10、10.4、10.7）
package docker

import (
	"context"
	"fmt"
	"io"
	"strings"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
)

// ContainerCreateOptions 容器创建参数
type ContainerCreateOptions struct {
	Name        string
	Image       string
	Cmd         []string
	Env         []string             // 环境变量（GPU 软限制 env 等）
	HostConfig  *container.HostConfig // 端口映射、资源限制、GPU、挂载
}

// CreateContainer 创建并启动容器
func (m *Manager) CreateContainer(ctx context.Context, opts ContainerCreateOptions) (string, error) {
	resp, err := m.cli.ContainerCreate(ctx,
		&container.Config{
			Image: opts.Image,
			Cmd:   opts.Cmd,
			Env:   opts.Env,
		},
		opts.HostConfig,
		nil, nil, opts.Name)
	if err != nil {
		return "", fmt.Errorf("创建容器失败: %w", err)
	}

	if err := m.cli.ContainerStart(ctx, resp.ID, container.StartOptions{}); err != nil {
		return "", fmt.Errorf("启动容器失败: %w", err)
	}

	return resp.ID, nil
}

// StartContainer 启动容器
func (m *Manager) StartContainer(ctx context.Context, id string) error {
	return m.cli.ContainerStart(ctx, id, container.StartOptions{})
}

// StopContainer 停止容器
func (m *Manager) StopContainer(ctx context.Context, id string) error {
	return m.cli.ContainerStop(ctx, id, container.StopOptions{})
}

// RestartContainer 重启容器
func (m *Manager) RestartContainer(ctx context.Context, id string) error {
	return m.cli.ContainerRestart(ctx, id, container.StopOptions{})
}

// RemoveContainer 删除容器
func (m *Manager) RemoveContainer(ctx context.Context, id string, force bool) error {
	return m.cli.ContainerRemove(ctx, id, container.RemoveOptions{Force: force})
}

// ExecInContainer 在容器中执行命令（用于 SSH 密码注入，任务 10.4/10.8）
func (m *Manager) ExecInContainer(ctx context.Context, containerID string, cmd []string) (string, error) {
	exec, err := m.cli.ContainerExecCreate(ctx, containerID, container.ExecOptions{
		Cmd:          cmd,
		AttachStdout: true,
		AttachStderr: true,
	})
	if err != nil {
		return "", fmt.Errorf("创建 exec 失败: %w", err)
	}

	resp, err := m.cli.ContainerExecAttach(ctx, exec.ID, container.ExecAttachOptions{})
	if err != nil {
		return "", fmt.Errorf("attach exec 失败: %w", err)
	}
	defer resp.Close()

	out, _ := io.ReadAll(resp.Reader)
	return string(out), nil
}

// InspectContainer 查看容器详情（含端口映射）
func (m *Manager) InspectContainer(ctx context.Context, id string) (types.ContainerJSON, error) {
	return m.cli.ContainerInspect(ctx, id)
}

// ListContainersByName 按名称过滤容器
func (m *Manager) ListContainersByName(ctx context.Context, name string) ([]types.Container, error) {
	return m.cli.ContainerList(ctx, container.ListOptions{
		All: true,
		Filters: filters.NewArgs(filters.Arg("name", name)),
	})
}

// ContainerExists 检查容器是否存在
func (m *Manager) ContainerExists(ctx context.Context, name string) (bool, error) {
	containers, err := m.ListContainersByName(ctx, name)
	if err != nil {
		return false, err
	}
	return len(containers) > 0, nil
}

// ExecAttachInteractive 交互式 exec（Tty），返回 hijacked 连接供双向读写（platform-refinements #2 在线终端）
func (m *Manager) ExecAttachInteractive(ctx context.Context, containerID string, cmd []string) (types.HijackedResponse, error) {
	exec, err := m.cli.ContainerExecCreate(ctx, containerID, container.ExecOptions{
		Cmd:          cmd,
		Tty:          true,
		AttachStdin:  true,
		AttachStdout: true,
		AttachStderr: true,
	})
	if err != nil {
		return types.HijackedResponse{}, fmt.Errorf("创建 exec 失败: %w", err)
	}
	resp, err := m.cli.ContainerExecAttach(ctx, exec.ID, container.ExecAttachOptions{Tty: true})
	if err != nil {
		return types.HijackedResponse{}, fmt.Errorf("attach exec 失败: %w", err)
	}
	return resp, nil
}

// ContainerLogs 获取容器日志（platform-refinements #2：tail 最近 N 条，不 follow--实时由前端轮询）
func (m *Manager) ContainerLogs(ctx context.Context, containerID string, tail string) (string, error) {
	reader, err := m.cli.ContainerLogs(ctx, containerID, container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Tail:        tail,
		Timestamps:  false,
		Follow:      false,
	})
	if err != nil {
		return "", fmt.Errorf("获取容器日志失败: %w", err)
	}
	defer reader.Close()
	out, _ := io.ReadAll(reader)
	return string(out), nil
}

// ParseImageRef 解析镜像引用为 name:tag
func ParseImageRef(ref string) (string, string) {
	parts := strings.SplitN(ref, ":", 2)
	if len(parts) == 1 {
		return parts[0], "latest"
	}
	return parts[0], parts[1]
}
