## Why

平台三轮迭代（`build-nexcompute-platform` → `platform-improvements` → `platform-refinements`）已使核心能力闭环可用，但投用中暴露三类影响落地与运维的缺口：受控端环境准备完全靠人工照 `docs/受控端环境准备.md` 逐步手敲 PowerShell，出错率高且无文件下发；受控端无版本可见性、无远程升级通道，现场维护只能 U 盘拷贝覆盖；网页端通知仍停留在"未读消息收件箱"被动查看，工单/容器变动无实时提醒、登录公告无强提示。此外一批小体验问题（工单缺详情与联系方式、权限矩阵缺恢复默认与确认、导师/学生系统信息误提示、硬件指纹来源不明）需一并收敛。本变更批量补齐，使受控端开箱即用、可远程运维、网页端实时可感。

## What Changes

**受控端环境自动配置**
- 受控端 GUI 新增"受控端环境准备"栏目，按 `docs/受控端环境准备.md` 的 9 个 `###` 章节生成 **9 个按钮**（一一对应，不合并）。点击按钮即新建一个**管理员权限的 PowerShell**，按行执行该步骤脚本，执行完成后**保持窗口打开**（不自动关闭）。
- 管理端管理员新增"受控端环境"功能模块：上传环境准备所需文件（`Docker Desktop Installer.exe`、NVIDIA Container Toolkit 各 deb 包等），受控端按 **MD5 校验**同步到本地存储池 `Env` 文件夹；新增"环境网盘同步"按钮手动触发同步。
- "离线安装 NVIDIA Container Toolkit"按钮：弹提示框引导用户在 WSL 内安装 Env 文件夹中的 deb 包，相关命令提供**一键复制**按钮。
- "修改 Docker Engine 配置"按钮：先启动 Docker Desktop，再弹窗引导用户在 Docker Engine 设置中粘贴配置，JSON 代码块提供**一键复制**按钮。

**受控端 OTA 升级**
- 管理端"物理实例"列表展示每台物理实例的受控端版本（`agentVersion`，心跳已上报，需接为构建期注入的真实版本）。
- 管理端管理员新增"受控端升级"功能模块：列出每个物理实例当前版本，提供**批量升级**与**单独升级**；管理员手动上传新版受控端 exe，经 file-transfer 下发，受控端替换自身 exe（**保留配置文件** `nexcompute-agent.json`）。
- 受控端自更新须**保证自身不崩溃**（下载→校验→替换→重启分离，替换期间进程存活），升级完成后**自动启动**新版。

**网页端实时响应**
- 工单变动、容器变动等通知在网页**右下角实时弹出** Toast（复用既有 `SseService`/`utils/sse.ts`，新增前端 Toast 组件）。
- 导师与管理员登录后，定向公告在**屏幕中央**弹窗提示，按钮"已读""下次再说"。
- 发布公告时"指定课题组"由单选改为**多选课题组**。

**工单**
- 工单管理新增"详情"视图，分为"已处理""待处理"两张表格。
- 用户提交工单新增"联系方式"字段，默认填账户信息的手机号。

**系统信息**
- 导师与学生的"系统信息"直接展示，不再显示"仅管理员可修改系统信息"提示。

**权限矩阵配置**
- 新增"恢复默认"按钮（恢复平台默认权限矩阵）。
- 修改后需**确认修改**方生效。

**硬件指纹**
- 文档化并强化硬件指纹来源（当前为 MAC + `host.HostID`/Windows MachineGuid），说明其变化诱因，评估是否引入更稳定的 SMBIOS UUID 作为主指纹。

## Capabilities

### New Capabilities
- `agent-env-prep`: 受控端环境准备——GUI 分步按钮拉起管理员 PowerShell 执行 `docs/受控端环境准备.md` 各章节脚本（窗口保持开启）；离线 NVIDIA Toolkit 与 Docker Engine 配置步骤提供引导弹窗与一键复制；管理端"受控端环境"模块上传文件并按 MD5 同步至受控端本地存储池 `Env` 文件夹，含手动"环境网盘同步"。
- `agent-ota`: 受控端 OTA 升级——管理员上传新版 exe，按物理实例批量/单独升级；受控端下载校验后替换自身 exe（保留配置）、不崩溃、升级后自动启动；管理端可见每实例版本。

### Modified Capabilities
- `controlled-agent`: GUI 新增"受控端环境准备"栏目与 9 个分步按钮；新增 `agent.upgrade` 自更新命令（保留配置、进程不中断、升级后自启）；`agentVersion` 由构建期 ldflags 注入常量（替代 `heartbeat.go` 硬编码 `"0.1.0"`）。
- `physical-instance`: 物理实例列表展示受控端版本；明确硬件指纹来源与变化诱因，必要时以 SMBIOS UUID 强化指纹稳定性。
- `notifications`: 新增右下角实时 Toast（工单/容器等变动，复用 SSE）；导师与管理员登录后公告屏幕中央弹窗（已读/下次再说）；公告"指定课题组"由单选改多选（`Announcement.targetId` 单值 → 多对多 `announcement_group` 关联）。
- `ticket-system`: 工单管理"详情"分"已处理/待处理"两表；用户提交工单新增"联系方式"字段（默认账户手机号）。
- `access-control`: 权限矩阵新增"恢复默认"与修改确认；导师/学生"系统信息"直接展示（移除"仅管理员可修改系统信息"提示）。

## Impact

**受控端（Go / Fyne）**
- `internal/gui/window.go`：新增"受控端环境准备" Card + 9 个按钮，按钮拉起管理员 PowerShell（复用 `internal/executil` + `runas` 提权），脚本内置、执行后不退出；离线 Toolkit 与 Docker Engine 步骤弹 `dialog` + 一键复制（`clipboard`）。
- `internal/config/config.go`：新增 `EnvDir`（存储池 `Env` 子目录）派生与配置项；`agentVersion` 改由 `internal/version` 包读取 ldflags 注入常量。
- `internal/heartbeat/heartbeat.go`：`agentVersion` 改读版本常量（移除硬编码 `"0.1.0"`）。
- `internal/agent/executor.go`：`dispatch` 新增 `agent.upgrade` 分支；新增 `internal/agent/upgrade_handlers.go`（下载→MD5 校验→备份当前 exe→替换→`cmd /c start` 拉起新版并自退出，保留 `nexcompute-agent.json`）。
- `internal/sysinfo/sysinfo.go`：`CollectMachineFingerprint` 评估并可选增加 SMBIOS UUID（`wmic csproduct get UUID` / Go SMBIOS 库）作为更稳定主指纹。
- `internal/filetransfer/downloader.go`：复用既有下载能力拉取 Env 文件与新 exe。
- `Makefile` / `build.ps1`：`VERSION` 经 ldflags `-X` 注入 `internal/version.Version`。

**管理端后端（SpringBoot）**
- 新增 `EnvFileController`/`EnvFileService`（管理员上传环境文件、记录 MD5、下发同步命令、手动触发）；Env 文件存储于既有 `${storage.root}/env`。
- 新增 `AgentOtaController`/`AgentOtaService`（上传新版 exe、按实例批量/单独下发 `agent.upgrade`、记录升级任务与结果）；exe 存于 `${storage.root}/agent-upgrade`。
- `PhysicalInstanceController`/`PhysicalInstanceService`：列表 DTO 暴露 `agentVersion`（实体已有该列）。
- `AnnouncementController`/`AnnouncementService`/`Announcement`：`targetScope=GROUP` 时 `targetId` 单值 → 多对多 `announcement_group`（Flyway 建表）；可见性判定按组集合匹配。
- `TicketController`/`TicketService`/`Ticket`：新增 `contact` 列（Flyway）；"详情"返回已处理/待处理分组。
- `PermissionController`/`PermissionService`：新增"恢复默认"端点 + 修改确认语义。
- `SseService`：复用 `pushToUser` 推送实时变动事件（容器/工单/存储池变动已在产生消息处补发 SSE）。
- `HeartbeatService`：`agentVersion` 已落库，无需结构变更。

**管理端前端（Vue 3 / AntD）**
- 新增 `views/admin/EnvFilesView.vue`（环境文件管理 + 同步按钮）、`views/admin/AgentUpgradeView.vue`（版本列表 + 批量/单独升级）。
- `views/instance/InstanceManageView.vue`/`InstanceStatusView.vue`：表格增"受控端版本"列。
- 新增 `components/RealtimeToast.vue`（挂载于 `BasicLayout.vue`，订阅 `utils/sse.ts`，右下角弹窗）；`components/AnnouncementLoginModal.vue`（登录后中央弹窗）。
- `views/announcement/AnnouncementManageView.vue`：`GROUP` 改 `a-select mode="multiple"`。
- `views/ticket/TicketManageView.vue`：增"详情"+已处理/待处理两表；`TicketListView.vue`：提交表单增"联系方式"（默认手机号）。
- `views/admin/PermissionMatrixView.vue`：增"恢复默认"按钮 + 修改确认弹窗。
- `views/admin/SystemInfoView.vue`：导师/学生移除"仅管理员可修改系统信息"提示、字段直接展示。
- `api/`：新增 `envFile.ts`、`agentUpgrade.ts`；`announcement.ts`/`ticket.ts`/`permission.ts` 扩展。

**基础设施**
- Flyway 迁移：`ticket` 增 `contact` 列；新增 `announcement_group` 表；`env_file`、`agent_upgrade_task` 元数据表（如需持久化）。
- `docker-compose.yml`：无变更（存储仍用命名卷）。
