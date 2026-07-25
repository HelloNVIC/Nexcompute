// Package docker - 镜像操作封装（任务 5.10、9.2、9.6）
package docker

import (
	"context"
	"fmt"
	"os"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	"github.com/docker/docker/api/types/image"
)

// ListImages 列出本地镜像
func (m *Manager) ListImages(ctx context.Context) ([]image.Summary, error) {
	return m.cli.ImageList(ctx, image.ListOptions{})
}

// LoadImage 从 tar 文件导入镜像（docker load，任务 9.6）
func (m *Manager) LoadImage(ctx context.Context, tarPath string) ([]string, error) {
	f, err := os.Open(tarPath)
	if err != nil {
		return nil, fmt.Errorf("打开 tar 失败: %w", err)
	}
	defer f.Close()

	resp, err := m.cli.ImageLoad(ctx, f)
	if err != nil {
		return nil, fmt.Errorf("load 镜像失败: %w", err)
	}
	defer resp.Body.Close()

	// 简化：返回空字符串列表，实际可解析输出流
	return nil, nil
}

// SaveImage 导出镜像为 tar（docker save，任务 9.2）
func (m *Manager) SaveImage(ctx context.Context, imageRef string, tarPath string) error {
	reader, err := m.cli.ImageSave(ctx, []string{imageRef})
	if err != nil {
		return fmt.Errorf("save 镜像失败: %w", err)
	}
	defer reader.Close()

	f, err := os.Create(tarPath)
	if err != nil {
		return fmt.Errorf("创建 tar 文件失败: %w", err)
	}
	defer f.Close()

	if _, err := f.ReadFrom(reader); err != nil {
		return fmt.Errorf("写入 tar 失败: %w", err)
	}
	return nil
}

// CommitContainer 将容器提交为镜像（docker commit，任务 9.2）
func (m *Manager) CommitContainer(ctx context.Context, containerID, repo, tag string) (string, error) {
	resp, err := m.cli.ContainerCommit(ctx, containerID, container.CommitOptions{
		Reference: fmt.Sprintf("%s:%s", repo, tag),
	})
	if err != nil {
		return "", fmt.Errorf("commit 容器失败: %w", err)
	}
	return resp.ID, nil
}

// ImageExists 检查镜像是否本地存在
func (m *Manager) ImageExists(ctx context.Context, ref string) (bool, error) {
	images, err := m.cli.ImageList(ctx, image.ListOptions{
		Filters: filters.NewArgs(filters.Arg("reference", ref)),
	})
	if err != nil {
		return false, err
	}
	return len(images) > 0, nil
}

// RemoveImage 删除镜像
func (m *Manager) RemoveImage(ctx context.Context, ref string) error {
	_, err := m.cli.ImageRemove(ctx, ref, image.RemoveOptions{Force: true})
	return err
}
