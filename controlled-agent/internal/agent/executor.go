// Package agent 实现命令执行器（任务 4.8、6.5、10.4、10.7）。
// 执行管理端通过 WS 派发的命令（Docker 管理、系统控制等），回传结果。
// 严格校验命令来源，仅执行经鉴权的管理端命令。
package agent

import (
	"log"
	"time"

	"github.com/nexcompute/controlled-agent/internal/config"
	"github.com/nexcompute/controlled-agent/internal/docker"
)

// Command 管理端下发的命令
type Command struct {
	ID        string         `json:"id"`
	Type      string         `json:"type"`       // 命令类型
	Token     string         `json:"token"`      // 鉴权令牌
	Payload   map[string]any `json:"payload"`    // 命令参数
	Timestamp int64          `json:"timestamp"`
}

// Result 命令执行结果
type Result struct {
	CommandID string `json:"commandId"`
	Success   bool   `json:"success"`
	Output    string `json:"output"`
	Error     string `json:"error,omitempty"`
	Timestamp int64  `json:"timestamp"`
}

// Executor 命令执行器
type Executor struct {
	cfg       *config.Config
	docker    *docker.Manager
	envSyncer *EnvSyncer
	quitFunc  func() // 升级后优雅退出（由 GUI 注入，marshal 到主线程）
	sender    MessageSender
}

// NewExecutor 创建执行器
func NewExecutor(cfg *config.Config) *Executor {
	return &Executor{cfg: cfg, sender: noopSender{}}
}

// SetMessageSender 注入消息发送器（wsclient），用于长时命令回传 progress（D2）
func (e *Executor) SetMessageSender(s MessageSender) {
	if s == nil {
		e.sender = noopSender{}
		return
	}
	e.sender = s
}

// sendProgress 回传进度消息（不替代最终 Result）
func (e *Executor) sendProgress(commandID, stage string, percent int) {
	if e.sender == nil {
		return
	}
	if percent < 0 {
		percent = 0
	}
	if percent > 100 {
		percent = 100
	}
	e.sender.SendProgress(ProgressMessage{
		Type: "progress", CommandID: commandID, Stage: stage, Percent: percent,
		Timestamp: time.Now().UnixMilli(),
	})
}

// SetDockerManager 注入 Docker 管理器
func (e *Executor) SetDockerManager(m *docker.Manager) {
	e.docker = m
}

// SetEnvSyncer 注入环境文件同步器（env.sync 命令调用其 RunNow）
func (e *Executor) SetEnvSyncer(s *EnvSyncer) {
	e.envSyncer = s
}

// SetQuitFunc 注入退出函数（升级完成后调用以优雅退出受控端，D7）
func (e *Executor) SetQuitFunc(f func()) {
	e.quitFunc = f
}

// ValidateSource 校验命令来源是否经鉴权（任务 4.8）
func (e *Executor) ValidateSource(cmd *Command) bool {
	if cmd.Token == "" || cmd.Token != e.cfg.AgentToken {
		log.Printf("[security] 拒绝未鉴权命令: type=%s id=%s", cmd.Type, cmd.ID)
		return false
	}
	return true
}

// Execute 执行命令并返回结果
// 先校验命令来源（defense-in-depth，WS 客户端也会校验），再分发执行。
func (e *Executor) Execute(cmd *Command) Result {
	log.Printf("[agent] 执行命令: type=%s id=%s", cmd.Type, cmd.ID)

	result := Result{
		CommandID: cmd.ID,
		Timestamp: time.Now().UnixMilli(),
	}

	// 命令来源校验（任务 4.8）
	if !e.ValidateSource(cmd) {
		result.Success = false
		result.Error = "命令来源未鉴权，已拒绝"
		return result
	}

	output, err := e.dispatch(cmd)
	if err != nil {
		result.Success = false
		result.Error = err.Error()
	} else {
		result.Success = true
		result.Output = output
	}
	return result
}

// dispatch 按命令类型分发，具体实现在后续任务中完善
func (e *Executor) dispatch(cmd *Command) (string, error) {
	switch cmd.Type {
	// 本地管理员密码接收（platform-refinements 11.3：全局明文密码 config.set_admin_password）
	case "config.set_admin_password":
		return e.handleSetAdminPassword(cmd)

	// 系统控制（任务 6.5）
	case "system.restart":
		return e.handleRestart(cmd)
	case "system.screen_off":
		return e.handleScreenOff(cmd)
	case "system.powershell":
		return e.handlePowerShell(cmd)

	// 容器生命周期（任务 10.4、10.7）
	case "container.create":
		return e.handleContainerCreate(cmd)
	case "container.start", "container.stop", "container.restart", "container.rm":
		return e.handleContainerLifecycle(cmd)
	case "container.reset_ssh":
		return e.handleResetSSH(cmd)
	case "container.logs":
		return e.handleContainerLogs(cmd)
	case "terminal.open":
		return e.handleTerminalOpen(cmd)
	case "terminal.write":
		return e.handleTerminalWrite(cmd)
	case "terminal.read":
		return e.handleTerminalRead(cmd)
	case "terminal.close":
		return e.handleTerminalClose(cmd)

	// 端口占用查询（platform-refinements #6）
	case "port.query_used":
		return e.handleQueryUsedPorts(cmd)

	// 存储池（任务 8.3、8.6）
	case "storage.create_dir":
		return e.handleStorageCreateDir(cmd)
	case "storage.delete_dir":
		return e.handleStorageDeleteDir(cmd)
	// 存储池文件管理（platform-refinements #3：浏览/打包下载/上传）
	case "storage.list_files":
		return e.handleStorageListFiles(cmd)
	case "storage.archive":
		return e.handleStorageArchive(cmd)
	case "storage.upload_file":
		return e.handleStorageUploadFile(cmd)
	case "storage.migration_upload", "storage.migration_download":
		return e.handleStorageMigration(cmd)
	case "storage.migration_cleanup":
		return e.handleStorageMigrationCleanup(cmd)

	// 镜像（任务 9.2、9.6、9.8）
	case "image.commit":
		return e.handleImageCommit(cmd)
	case "image.load":
		return e.handleImageLoad(cmd)
	case "image.sync_public":
		return e.handleImageSyncPublic(cmd)

	// 环境文件同步（platform-env-ota-realtime D4：手动"环境网盘同步"下发）
	case "env.sync":
		return e.handleEnvSync(cmd)

	// 受控端 OTA 自更新（platform-env-ota-realtime D7）
	case "agent.upgrade":
		return e.handleUpgrade(cmd)

	// 受控端日志远端查看（platform-audit-logging-ux D8：列目录 + 尾 N 行）
	case "agent.log":
		return e.handleAgentLog(cmd)

	// 文件传输（任务 7.4、7.5）
	case "file.upload", "file.download":
		return e.handleFileTransfer(cmd)

	// 进程采集（任务 11.6）
	case "process.list":
		return e.handleProcessList(cmd)

	default:
		log.Printf("[agent] 未知命令类型: %s", cmd.Type)
		return "", errUnknownCommand(cmd.Type)
	}
}
