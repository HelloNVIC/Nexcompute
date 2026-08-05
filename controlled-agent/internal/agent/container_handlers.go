// Package agent - 容器命令处理器（任务 10.4、10.7、10.8）
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/mount"
	"github.com/docker/go-connections/nat"

	docker "github.com/nexcompute/controlled-agent/internal/docker"
)

// handleContainerCreate 容器创建执行（任务 10.4）
// docker run + 端口映射 + 容器级 SSH 密码注入 via docker exec + GPU 显存软限制 env 注入
func (e *Executor) handleContainerCreate(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	imageRef, _ := cmd.Payload["imageRef"].(string)
	sshPassword, _ := cmd.Payload["sshPassword"].(string)
	envRaw, _ := cmd.Payload["env"].([]any)
	portMappingsRaw, _ := cmd.Payload["portMappings"].([]any)
	cpuLimit := toFloat(cmd.Payload["cpuLimit"])
	memoryLimit, _ := cmd.Payload["memoryLimit"].(float64)
	shmSize, _ := cmd.Payload["shmSize"].(float64)
	mountPath, _ := cmd.Payload["mountPath"].(string)
	// V31：存储池在容器内的挂载点（bind mount Target），由管理端按镜像默认值/用户表单解析；
	// 为空时回退 /workspace，保持与历史镜像兼容。
	mountPoint, _ := cmd.Payload["mountPoint"].(string)
	// platform-improvements 任务 2.2：对齐管理端 payload gpus 字段以便审计（受控端仍硬编码 --gpus all）
	gpus, _ := cmd.Payload["gpus"].(string)
	if gpus != "" {
		log.Printf("[container] gpus 审计字段: %s（实际透传: --gpus all）", gpus)
	}
	if imageRef == "" {
		return "", fmt.Errorf("imageRef 为空")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	// 构建环境变量（含 GPU 显存软限制 env）
	env := make([]string, 0, len(envRaw))
	for _, e := range envRaw {
		if s, ok := e.(string); ok {
			env = append(env, s)
		}
	}

	// 端口映射
	hostConfig := &container.HostConfig{}
	portBindings := nat.PortMap{}
	var portBindingStrs []string
	for _, pm := range portMappingsRaw {
		m, ok := pm.(map[string]any)
		if !ok {
			continue
		}
		cp := int(toInt(m["containerPort"]))
		hp := int(toInt(m["hostPort"]))
		containerPort := nat.Port(fmt.Sprintf("%d/tcp", cp))
		portBindings[containerPort] = []nat.PortBinding{{HostPort: fmt.Sprintf("%d", hp)}}
		portBindingStrs = append(portBindingStrs, fmt.Sprintf("%d->%d", hp, cp))
	}
	hostConfig.PortBindings = portBindings

	// platform-refinements #1：自动重启 + 开机自启（除非手动停止）
	hostConfig.RestartPolicy = container.RestartPolicy{Name: "unless-stopped"}

	// 资源限制（CPU/内存硬限制）
	if cpuLimit > 0 {
		hostConfig.Resources.NanoCPUs = int64(cpuLimit * 1e9)
	}
	if memoryLimit > 0 {
		hostConfig.Resources.Memory = int64(memoryLimit)
	}
	if shmSize > 0 {
		hostConfig.ShmSize = int64(shmSize)
	}

	// GPU 透传（一机一卡，--gpus all）
	// 注意：Docker DeviceRequest 不能同时设 Count 和 DeviceIDs，二选一。
	// Count=-1 表示使用全部 GPU（等价 --gpus all）。
	hostConfig.DeviceRequests = []container.DeviceRequest{
		{Driver: "nvidia", Count: -1},
	}

	// 存储池挂载（Source=宿主池路径，Target=容器内挂载点，默认 /workspace）
	if mountPath != "" {
		target := mountPoint
		if target == "" {
			target = "/workspace"
		}
		hostConfig.Mounts = append(hostConfig.Mounts, mount.Mount{
			Type:   mount.TypeBind,
			Source: mountPath,
			Target: target,
		})
	}

	// 创建并启动容器
	containerName := fmt.Sprintf("nex-%d", time.Now().UnixNano())
	opts := docker.ContainerCreateOptions{
		Name:       containerName,
		Image:      imageRef,
		Env:        env,
		HostConfig: hostConfig,
	}
	containerID, err := e.docker.CreateContainer(ctx, opts)
	if err != nil {
		return "", fmt.Errorf("创建容器失败: %w", err)
	}
	log.Printf("[container] 容器已创建: %s (%s)", containerName, containerID)

	// SSH 密码注入 via docker exec（任务 10.4 spec）
	if sshPassword != "" {
		if err := e.injectSSHCredentials(ctx, containerID, sshPassword); err != nil {
			log.Printf("[container] SSH 密码注入失败: %v", err)
		}
	}

	// 返回容器 ID 与端口映射
	result, _ := json.Marshal(map[string]any{
		"containerId":   containerID,
		"containerName": containerName,
		"portBindings":  portBindingStrs,
	})
	return string(result), nil
}

// handleContainerLogs 查看容器日志（platform-refinements #2）：tail 最近 N 条，实时由前端轮询
func (e *Executor) handleContainerLogs(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}
	containerID, _ := cmd.Payload["containerId"].(string)
	tail, _ := cmd.Payload["tail"].(string)
	if tail == "" {
		tail = "100"
	}
	if containerID == "" {
		return "", fmt.Errorf("containerId 为空")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	logs, err := e.docker.ContainerLogs(ctx, containerID, tail)
	if err != nil {
		return "", fmt.Errorf("获取日志失败: %w", err)
	}
	return fmt.Sprintf(`{"logs":%s}`, mustQuoteJSON(logs)), nil
}

// mustQuoteJSON 将字符串转为 JSON 字符串字面量（简单转义）
func mustQuoteJSON(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 {
				b.WriteString(fmt.Sprintf(`\u%04x`, r))
			} else {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
	return b.String()
}

// handleContainerLifecycle 容器生命周期（任务 10.7）
func (e *Executor) handleContainerLifecycle(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	containerID, _ := cmd.Payload["containerId"].(string)
	if containerID == "" {
		return "", fmt.Errorf("containerId 为空")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	switch cmd.Type {
	case "container.start":
		return "started", e.docker.StartContainer(ctx, containerID)
	case "container.stop":
		return "stopped", e.docker.StopContainer(ctx, containerID)
	case "container.restart":
		return "restarted", e.docker.RestartContainer(ctx, containerID)
	case "container.rm":
		return "removed", e.docker.RemoveContainer(ctx, containerID, true)
	default:
		return "", fmt.Errorf("未知生命周期命令: %s", cmd.Type)
	}
}

// handleResetSSH SSH 密码即时重置（任务 10.8）
func (e *Executor) handleResetSSH(cmd *Command) (string, error) {
	if e.docker == nil {
		return "", fmt.Errorf("Docker 管理器未初始化")
	}

	containerID, _ := cmd.Payload["containerId"].(string)
	password, _ := cmd.Payload["password"].(string)
	if containerID == "" || password == "" {
		return "", fmt.Errorf("containerId 或 password 为空")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := e.injectSSHCredentials(ctx, containerID, password); err != nil {
		return "", fmt.Errorf("SSH 密码重置失败: %w", err)
	}
	return "ssh_reset_ok", nil
}

// injectSSHCredentials 通过 docker exec 注入 SSH 密码
// 假设容器内 SSH 用户为 root，使用 chpasswd 设置密码
func (e *Executor) injectSSHCredentials(ctx context.Context, containerID, password string) error {
	// 确保 sshd 运行
	if _, err := e.docker.ExecInContainer(ctx, containerID, []string{"sh", "-c", "service ssh start || service sshd start || /usr/sbin/sshd"}); err != nil {
		log.Printf("[container] 启动 sshd 警告: %v", err)
	}

	// 设置 root 密码
	cmd := fmt.Sprintf("echo 'root:%s' | chpasswd", escapePassword(password))
	if _, err := e.docker.ExecInContainer(ctx, containerID, []string{"sh", "-c", cmd}); err != nil {
		return fmt.Errorf("chpasswd 失败: %w", err)
	}
	log.Printf("[container] SSH 密码已注入: %s", containerID)
	return nil
}

func toFloat(v any) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case int64:
		return float64(n)
	}
	return 0
}

func toInt(v any) int64 {
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int:
		return int64(n)
	case int64:
		return n
	}
	return 0
}

func escapePassword(s string) string {
	return strings.ReplaceAll(s, "'", "'\\''")
}
