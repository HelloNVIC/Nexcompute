## Context

平台三轮迭代后核心能力已闭环。本轮新增三大功能域（受控端环境自动配置、受控端 OTA 升级、网页端实时响应）并收敛若干小体验问题。关键现状（经受控端/后端/前端代码确认）：

- **环境准备无产品化**：`docs/受控端环境准备.md` 列 9 个 `###` 章节（安装 Docker / 安装 WSL / 检查显卡驱动 / 离线安装 NVIDIA Container Toolkit / 验证 Toolkit 并配置运行时 / 修改 Docker Engine 配置 / 创建 GPU 容器并启动 Jupyter / 环境测试 / GPU 压力测试），受控端无对应 GUI，全靠现场照文档手敲。
- **受控端无版本治理**：`internal/heartbeat/heartbeat.go:121` 硬编码 `"agentVersion": "0.1.0"`；`Makefile` 已有 `VERSION := 0.1.0` 与 `LDFLAGS := -X main.version=$(VERSION)` 但 `main.go` 未声明 `version` 变量、`heartbeat.go` 未读取。后端 `PhysicalInstance.agentVersion` 列已存在、`HeartbeatService` 已落库，但前端物理实例列表未展示。
- **文件下发链路已具备**：`/agent/file/**`（`FileTransferController`）已 agent-token 鉴权，公共镜像同步（`internal/agent/public_image_syncer.go`）已复用该链路按 MD5 比对下载。Env 文件同步可同模式复用。
- **命令分发已有**：`internal/agent/executor.go:dispatch` 为 switch，新增 `agent.upgrade` 分支即可；命令经 `AgentWebSocketHandler` 下发，`Executor.ValidateSource` 校验 token。
- **SSE 已通**：`SseService.pushToUser(userId, eventType, data)` + `SseController` + 前端 `utils/sse.ts` 已连通，前端 `NotificationInbox.vue` 为被动收件箱，无主动 Toast。
- **公告单课题组**：`Announcement.targetScope`（ALL/GROUP/ROLE）+ 单值 `targetId`；前端 `AnnouncementManageView.vue` `GROUP` 分支为单选 `a-select`。
- **工单无联系方式、无分组详情**：`Ticket` 无 `contact` 列；`TicketManageView.vue` 仅列表。
- **权限矩阵无恢复默认/确认**：`PermissionMatrixView.vue` 仅有 `saving`/`fieldSaving` 提示。
- **系统信息误提示**：`SystemInfoView.vue` 非 owner 显示 `a-alert "仅管理员可修改系统信息"`（导师/学生亦见）。
- **硬件指纹**：`sysinfo.CollectMachineFingerprint` = MAC（首个非虚拟网卡）+ `host.HostID`（gopsutil，Windows 下为注册表 `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`）；`HeartbeatService` 按 `mac+machineCode` 去重复用编号。

## Goals / Non-Goals

**Goals:**
- 受控端 GUI 一键分步执行环境准备，管理员 PowerShell 提权运行、窗口保持开启；关键步骤（Toolkit 离线安装、Docker Engine 配置）弹窗引导 + 一键复制。
- 管理端集中托管环境文件，按 MD5 同步至受控端本地 `Env` 文件夹，支持手动触发。
- 受控端版本可见、可远程升级（批量/单独），自更新保留配置、不崩溃、升级后自启。
- 网页端右下角实时 Toast（工单/容器变动）；导师/管理员登录公告中央弹窗（已读/下次再说）；公告多选课题组。
- 工单详情分已处理/待处理两表；提交工单带联系方式（默认手机号）。
- 导师/学生系统信息直接展示；权限矩阵恢复默认 + 修改确认。
- 文档化硬件指纹变化诱因，评估 SMBIOS UUID 强化。

**Non-Goals:**
- 不做受控端自动检查更新并自行升级（仅管理员手动触发；自动升级仅指"升级流程无需人工干预替换重启"）。
- 不引入 OTA 灰度/回滚自动化（保留 `.bak` 供手动回滚，不做版本注册表与自动回滚）。
- 不改公共镜像库同步机制（Env 文件同步为独立链路，不复用镜像同步调度，仅复用 file-transfer 通道）。
- 不做 Toast 取代未读消息收件箱（收件箱仍为永久记录，Toast 为瞬时提醒）。
- 不对受控端 exe 做签名校验（仅 MD5 完整性校验；如需可后续加签名）。

## Decisions

### D1. 受控端版本号构建期注入
- **Decision**：新增 `internal/version/version.go` 声明 `var Version = "0.1.0-dev"`；`Makefile`/`build.ps1` 以 `go build -ldflags "-X github.com/nexcompute/controlled-agent/internal/version.Version=$(VERSION)"` 注入（`build.ps1` 从 git tag 或参数取 VERSION）；`heartbeat.go` 改读 `version.Version`（移除硬编码 `"0.1.0"`）。后端 `PhysicalInstance.agentVersion` 已落库，前端列表直接读。
- **Alternatives**：(a) 运行时从 `/version` 文件读--rejected，易丢、易被改；(b) 嵌 `debug.ReadBuildInfo`--rejected，无显式版本号、依赖 VCS 信息不稳定。
- **Rationale**：ldflags 注入是 Go 单二进制版本治理标准做法，`Makefile` 已半成型，补齐即可。

### D2. 9 个按钮的章节映射
- **Decision**：`docs/受控端环境准备.md` 含 9 个 `###` 章节，按章节一一对应生成 9 个按钮，不合并：
  1. 安装 Docker（执行 `Docker Desktop Installer.exe`，位于 Env 文件夹）
  2. 安装 WSL 环境（`wsl --install` 等）
  3. 检查显卡驱动（`nvidia-smi`）
  4. 离线安装 NVIDIA Container Toolkit（弹窗引导 + 一键复制 dpkg 命令）
  5. 验证 Toolkit 安装并配置运行时（`nvidia-ctk --version` / `nvidia-ctk runtime configure`）
  6. 修改 Docker Engine 配置（启动 Docker Desktop + 弹窗 + 一键复制 JSON）
  7. 创建 GPU 容器并启动 Jupyter（`docker run --gpus all ...`）
  8. 环境测试（容器内 PyTorch/CUDA 连通性验证）
  9. GPU 压力测试（后台容器内矩阵乘法压测）
- **Alternatives**：合并"环境测试"与"GPU 压力测试"为 8 个按钮--rejected，用户明确不合并、按 9 章一一对应。

### D3. 管理员 PowerShell 提权与窗口保持
- **Decision**：按钮点击经 `executil` 调 `ShellExecute` with verb `runas` 拉起 `powershell.exe -NoExit -NoProfile -Command "<脚本>"`。`-NoExit` 保证执行完不关闭（满足"运行完成后不需要自动关闭"）。多行脚本以分号串联或写临时 `.ps1` 后 `powershell -NoExit -File`。受控端本身已以管理员权限运行（`platform-refinements` 11.x），但 PowerShell 单独 `runas` 保证即便受控端未提权亦可用；若受控端已是管理员则直接 `Start-Process powershell -Verb RunAs`。
- **Alternatives**：直接 `os/exec` 起非提权 PowerShell--rejected，Docker/WSL 安装需管理员。
- **Risk**：UAC 弹窗需用户确认--无法绕过（无服务化），文档化即可。

### D4. Env 文件 MD5 同步链路
- **Decision**：管理端 `${storage.root}/env/`（`NexcomputeProperties` 既有的 `storageRoot` 下 `env` 子目录）托管环境文件；`env_file` 元数据表记录 `{filename, md5, size, uploadedAt}`。新增 `GET /api/agent/env/list`（`/agent/**` agent-token 鉴权，仿 `AgentImageController`）返回文件清单；受控端 `internal/agent/env_syncer.go`（仿 `public_image_syncer.go`）对比本地 `${storageRoot}/Env` 下文件 MD5，缺失/不一致者经 `/agent/file/**` 下载并校验。新增"环境网盘同步"按钮下发 `env.sync` 命令立即触发（除定时外）。Env 目录 = `${storageRoot}/Env`（与存储池根目录同级子目录，命名隔离）。
- **Alternatives**：复用公共镜像同步调度--rejected，语义不同（镜像同步有可见性/READY 状态机，Env 为纯文件分发）。
- **Rationale**：与既有 `public_image_syncer` 模式一致，受控端 MD5 比对逻辑可复用。

### D5. 离线 NVIDIA Toolkit 引导 + 一键复制
- **Decision**："离线安装 NVIDIA Container Toolkit" 按钮点击不直接执行 dpkg（须在 WSL 内、且依赖 Env 文件已同步），改为弹 Fyne `dialog`（`dialog.ShowCustom`）展示：说明文案（"请先将 Env 文件夹中 4 个 deb 包复制到 WSL 用户目录，再在 WSL 内按顺序执行"）+ 4 条 `sudo dpkg -i ...` 命令代码块 + "一键复制"按钮（`fyne.io/fyne/v2:clipboard` `SetContent`）。用户手动在 WSL 执行。
- **Alternatives**：受控端自动经 `wsl -e` 注入命令--rejected，依赖顺序与 WSL 环境状态不可控、失败难定位。

### D6. Docker Engine 配置引导
- **Decision**："修改 Docker Engine 配置" 按钮点击：先 `executil` 启动 Docker Desktop（`cmd /c start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"`，若路径不存在则 `where`/注册表查），再弹 Fyne `dialog` 展示 JSON 配置代码块（来自文档"修改Docker Engine配置"章节）+ "一键复制" + 说明（"打开 Docker Desktop > Settings > Docker Engine，粘贴以下配置后 Apply & Restart"）。
- **Risk**：Docker Desktop 路径因安装而异-- Mitigation：多路径探测 + 失败提示用户手动打开。

### D7. OTA 升级流程（替换运行中 exe）
- **Decision**：管理员经"受控端升级"模块上传新版 exe（`POST /api/admin/agent-upgrade/upload`，存 `${storage.root}/agent-upgrade/{version}.exe`，记 MD5）；选择实例后后端经 WS 下发 `agent.upgrade` 命令（payload `{fileId/version/md5/downloadUrl}`）；受控端 `internal/agent/upgrade_handlers.go` 处理：
  1. 经 `/agent/file/**` 下载新版 exe 至 `os.TempDir()\nexcompute-agent.new.exe`，MD5 校验失败则回传失败、不替换。
  2. 备份当前 exe 至 `nexcompute-agent.exe.bak`（与当前 exe 同目录）。
  3. 写 `updater.bat`（同目录）内容：`@echo off` / `timeout /t 2 /nobreak >nul`（等当前进程退出）/ `move /Y new.exe agent.exe` / `start "" agent.exe` / `del updater.bat`。
  4. `cmd /c start "" updater.bat` 拉起 updater，随即 `app.Quit()` 优雅退出（不 kill，避免文件锁未释放前替换）。
  5. updater 完成替换后 `start` 新版 exe，新版读取既有 `nexcompute-agent.json`（配置保留，因仅替换 exe 不动 json）。
- **配置保留**：`nexcompute-agent.json`、`Env` 文件夹、存储池根目录均不受影响（仅替换 exe）。
- **不崩溃保证**：下载/校验/备份/updater 写盘均在本进程运行时完成；唯一退出点是末尾 `app.Quit()`（优雅），由独立 updater 完成替换与重启，进程不存在"半替换"中间态。
- **Alternatives**：(a) 受控端直接 `os.Rename` 替换自身 exe--rejected，Windows 下运行中 exe 文件锁不可写；(b) 安装为 Windows 服务再 `sc stop/start`--rejected，超出当前架构（受控端为用户态托盘进程）。
- **Risk**：updater.bat 执行失败致无新版启动-- Mitigation：保留 `.bak`，updater 失败时回滚 `move agent.exe.bak agent.exe`；新版启动失败用户可手动运行 `.bak`。

### D8. 右下角实时 Toast
- **Decision**：新增前端 `components/RealtimeToast.vue`，挂载于 `BasicLayout.vue`（登录后布局）。订阅既有 `utils/sse.ts` 的事件流，新增事件类型 `container.changed`/`ticket.changed`/`storage.changed`（后端在产生未读消息处同时 `sseService.pushToUser` 推送精简 payload `{type, title, message, link}`）。Toast 右下角堆叠展示，自动消失（默认 5s），点击跳转对应模块。与 `NotificationInbox`（永久记录）并存。
- **Alternatives**：复用未读消息轮询--rejected，已有 SSE 更实时且无轮询开销。

### D9. 登录公告中央弹窗
- **Decision**：新增前端 `components/AnnouncementLoginModal.vue`，挂载于 `BasicLayout.vue`。登录成功后（角色为 MENTOR/ADMIN；学生公告见既有公告查看）调用 `GET /api/announcements/login-banner`（返回定向该用户且未读的公告列表），屏幕中央 `a-modal` 展示，按钮"已读"（标记该公告已读、展示下一条）与"下次再说"（`localStorage` 记 `dismissedAnnouncements` + 本会话不再弹）。
- **Note**：学生登录公告按既有"公告查看"流程，本项聚焦导师/管理员（用户明确）。

### D10. 公告多选课题组
- **Decision**：`Announcement.targetScope=GROUP` 支持多选。新增 Flyway `announcement_group` 关联表（`announcement_id`, `group_id`，联合唯一）。`AnnouncementService` 发布/可见性判定改为按组集合匹配用户课题组（`targetId` 单值废弃，旧数据迁移：`UPDATE announcement_group SELECT id, target_id FROM announcement WHERE target_scope='GROUP' AND target_id IS NOT NULL`）。前端 `AnnouncementManageView.vue` `GROUP` 分支 `a-select mode="multiple"`。
- **Alternatives**：`targetId` 存逗号分隔串--rejected，无法走外键、查询低效。
- **Migration**：Flyway 先建表再回填；`target_id` 列保留一段时间兼容后删。

### D11. 工单详情 + 联系方式
- **Decision**：`Ticket` 增 `contact` 列（VARCHAR，nullable，Flyway）；`TicketCreateRequest`/提交表单增 `contact`，前端默认填账户 `phone`（`UserInfoDto`/`ProfileView` 既有手机号）；空则允许提交（不强制）。工单管理"详情"：`TicketManageView.vue` 增"详情"抽屉/页，内含"已处理"（status=CLOSED）与"待处理"（status=PENDING）两张表格，复用 `TicketService` 既有查询按状态分组返回（或前端分两组渲染）。
- **Alternatives**：分页 Tab--rejected，用户要求同屏两表对比。

### D12. 系统信息展示（导师/学生）
- **Decision**：`SystemInfoView.vue` 移除 `<a-alert v-else ... "仅管理员可修改系统信息">` 分支；导师/学生进入直接展示系统信息字段（`disabled`，只读）；仅管理员（`isOwner`/ADMIN 角色）字段可编辑。无后端变更（数据接口已按角色返回）。
- **Rationale**：用户明确导师/学生直接显示即可。

### D13. 权限矩阵恢复默认 + 修改确认
- **Decision**：后端 `PermissionController` 新增 `POST /api/admin/permissions/reset-default`（`PermissionService.resetToDefault` 从 `platform-refinements` 既有默认矩阵种子恢复全部角色×模块）。前端 `PermissionMatrixView.vue` 新增"恢复默认"按钮（`a-popconfirm` 二次确认）；既有保存改为"修改后弹确认框（`a-modal` '确认修改权限矩阵？'），确认方提交"。
- **Risk**：恢复默认覆盖自定义-- Mitigation：二次确认 + 操作记审计日志。

### D14. 硬件指纹来源与强化
- **Decision**：文档化当前指纹 = MAC（首个非虚拟网卡）+ MachineCode（`host.HostID` = Windows `MachineGuid`）。变化诱因：
  - **MAC 变化**：网卡更换/增删、USB 网卡插拔、Hyper-V/vEthernet 等虚拟网卡被选为首个非虚拟网卡、MAC 随机化（Windows WiFi MAC 随机化）/克隆、驱动重装致枚举顺序变化、主板集成网卡损坏。
  - **MachineGuid 变化**：操作系统重装、`sysprep`/克隆部署重置、注册表 `MachineGuid` 被手动修改/重置、部分系统优化/重置工具。
  - **结论**：MAC 不稳定（用户可见、易变），MachineGuid 中等稳定（OS 重装才变）。两者组合在"换网卡"或"重装系统"时会变，导致重复注册。
- **强化（可选，建议）**：`CollectMachineFingerprint` 增第三维 SMBIOS UUID（`wmic csproduct get UUID` 或 Go SMBIOS 库 `dmidecode` 类），SMBIOS UUID 由主板 BIOS 决定，OS 重装/网卡更换不变，作为**主指纹**；MAC/MachineGuid 作为辅助。去重匹配优先 SMBIOS UUID，UUID 缺失（虚拟机/部分设备返回全 0）回退 MachineGuid 再回退 MAC。
- **Alternatives**：仅保留现状不改--rejected，用户问"会因什么变化"表明关切稳定性，应强化。
- **Risk**：SMBIOS UUID 在 VM/部分主板可能为空/全 0-- Mitigation：回退链。

## Risks / Trade-offs

- **[UAC 弹窗] -> 无法绕过**：每按钮拉起管理员 PowerShell 触发 UAC；文档化，受控端若已以管理员运行则部分场景可免。
- **[OTA 替换失败致受控端失联] -> 备份 + 回滚**：`.bak` 保留，updater 失败回滚；新版启动失败可手动跑 `.bak`。
- **[Env 文件体积大] -> MD5 增量同步**：仅 MD5 不一致才下载；file-transfer 已支持分块续传。
- **[多选公告迁移] -> 数据回填**：Flyway 建 `announcement_group` 后回填旧 `target_id`，保留列兼容。
- **[SMBIOS UUID 缺失] -> 回退链**：UUID 空/全 0 时回退 MachineGuid 再 MAC，不阻断注册。
- **[Toast 打扰] -> 自动消失 + 限频**：5s 自动消失、同事件去抖、可后续加"勿扰"。

## Migration Plan

1. **Flyway**（顺序）：
   - `ticket` 增 `contact VARCHAR(100)`。
   - 新建 `announcement_group`（`announcement_id`, `group_id`），回填旧 `target_id`。
   - 新建 `env_file`（`id`, `filename`, `md5`, `size`, `uploaded_at`, `uploaded_by`）。
   - 新建 `agent_upgrade_task`（`id`, `instance_id`, `version`, `md5`, `status`, `created_at`, `finished_at`）--如需持久化升级任务记录。
2. **后端**：先上 env/ota/announcement/ticket/permission 端点与 SSE 事件，再上前端。
3. **受控端**：版本注入 + env 同步 + GUI 按钮 + `agent.upgrade` 一同发布；旧受控端不受影响（新命令未下发则不执行）。
4. **回滚**：Flyway 迁移可回退（drop 列/表）；OTA `.bak` 保留供手动回滚受控端；Env 文件同步为新增不影响既有。

## Open Questions

- OTA 是否需要"批量升级"的并发上限与失败重试策略？（建议：串行下发、单实例失败不影响其他、支持重试，详见 tasks。）
- 实时 Toast 事件范围是否包含镜像/存储池变动？（建议：先容器+工单，后续按需扩展。）
- 版本号来源：`Makefile` 手填 vs git tag 自动？（建议：`build.ps1` 优先取 git tag，无 tag 用 `Makefile VERSION`。）
