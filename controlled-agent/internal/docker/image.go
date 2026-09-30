// Package docker - 镜像操作封装（任务 5.10、9.2、9.6）
package docker

import (
	"context"
	"fmt"
	"io"
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

// ImageSize 返回镜像大小（字节，registry-image-distribution D4：commit push 后回传）。
func (m *Manager) ImageSize(ctx context.Context, ref string) (int64, error) {
	inspect, err := m.cli.ImageInspect(ctx, ref)
	if err != nil {
		return 0, fmt.Errorf("查询镜像大小失败（%s）: %w", ref, err)
	}
	return inspect.Size, nil
}

// RemoveImage 删除镜像
func (m *Manager) RemoveImage(ctx context.Context, ref string) error {
	_, err := m.cli.ImageRemove(ctx, ref, image.RemoveOptions{Force: true})
	return err
}

// TagImage 给镜像打新标签（docker tag，registry-image-distribution D4：commit 后指向私有仓库）。
func (m *Manager) TagImage(ctx context.Context, sourceRef, targetRef string) error {
	if err := m.cli.ImageTag(ctx, sourceRef, targetRef); err != nil {
		return fmt.Errorf("tag 镜像失败（%s -> %s）: %w", sourceRef, targetRef, err)
	}
	return nil
}

// PushImage 推送镜像至仓库（docker push，D4）。返回进度流（JSON 行，逐层上传状态），
// 由调用方读取至 EOF 方视为推送完成。私有仓库 insecure HTTP 无认证，PushOptions 零值即可。
func (m *Manager) PushImage(ctx context.Context, fullRef string) (io.ReadCloser, error) {
	reader, err := m.cli.ImagePush(ctx, fullRef, image.PushOptions{})
	if err != nil {
		return nil, fmt.Errorf("push 镜像失败（%s）: %w", fullRef, err)
	}
	return reader, nil
}

// PullImage 从仓库拉取镜像（docker pull，D5）。返回进度流（JSON 行，逐层下载状态），
// 由调用方读取至 EOF 方视为拉取完成。
func (m *Manager) PullImage(ctx context.Context, fullRef string) (io.ReadCloser, error) {
	reader, err := m.cli.ImagePull(ctx, fullRef, image.PullOptions{})
	if err != nil {
		return nil, fmt.Errorf("pull 镜像失败（%s）: %w", fullRef, err)
	}
	return reader, nil
}
