## Context

平台首版（`build-nexcompute-platform`，全部任务已完成、未归档）已落地为代码：管理端 SpringBoot、前端 Vue/AntD、受控端 Go。在实际使用中暴露 12 项缺口（详见 proposal）。本变更为对这些缺口的批量修复与增强。

经三端代码探查确认的现状要点（管理端/前端/受控端均已查清）：

- 存储池 `status` 为字符串（ACTIVE/MIGRATING/MIGRATED），无离线态，且未与物理机在线状态关联；`pool_path` 由受控端 `storage.create_dir` 回填。
- 容器创建命令 `buildDockerRunPayload` 发出 cpu/memory/shm/软限制 env，未显式携带 gpus；受控端 `handleContainerCreate` **已硬编码 `--gpus all`**（`DeviceRequests{Driver:"nvidia",Count:-1}`），GPU 透传实际已生效。
- 心跳载荷（`status` map）已含结构化 `GPUInfo`（name/memoryTotal/memoryUsed/driverVer，经 `nvidia-smi --query-gpu=...memory.total,memory.used,...`）与 gpuUsage/gpuTemp，但 `monitoring_history.gpu_memory_total/used` 从未填充；**主机 IP 完全未采集/回传**；**无容器状态回传**。后端 `HeartbeatRequest.ipAddress`/`gpuInfo` 顶层字段实际从未被受控端填充。
- 容器 `status` 仅由生命周期动作结果写入，外部停止后状态陈旧。
- 镜像：`image_metadata` 无应用端口列；`upload-tar` 前端为普通 `<input>`、无解析；创建容器仅发 `imageRef`，管理端**未触发既有 `image.load`**（受控端 `handleImageLoad` 已实现 `ImageExists` 跳过 + 下载 + `docker load`，但仅被公共镜像同步内部调用）；`public_image_sync` 表为死表。
- 前端：管理员同时显示"特需工单"+"工单管理"、"公告"+"公告管理"；`GroupInfoView` 仅调 `my()` 致管理员看空；容器创建表单镜像为自由文本、存储池下拉无状态、资源限制无默认与范围；容器列表无 IP 列、应用端口为平铺列表。
- 后端：管理员可建用户且 `group_id` 可空（无需课题组），`GroupController` 有 list/create/update/students 但**无 delete**。

约束：一机一卡、GPU 显存软共享（D9/D13）、直连模式、全出站通信、tar 镜像方案（D3）。

## Goals / Non-Goals

**Goals:**
- 修复 proposal 列出的 12 项缺口，使平台达到可实际投用。
- 引入用户/课题组资源配额，作为容器创建默认值与上限。
- 容器状态以心跳为实时来源并经 SSE 实时推送。
- 心跳完整回传主机 IP 与容器状态，并将既有结构化 GPU 显存持久化入库。
- 清理管理员菜单与课题组管理视图。

**Non-Goals:**
- GPU 硬隔离 / MIG（仍软共享，D13 不变）。
- 跨容器聚合配额预算（配额为每容器上限，非总量预算）。
- 穿透模式、多 GPU 调度、镜像层去重/回滚（延续 v1 Non-Goals）。
- 变更镜像 tar 存储位置（item 5 仅为文档化，不改路径）。

## Decisions

### D1: 存储池离线为计算型状态（非存储枚举）

**选择**：离线在查询时推导--`池离线 = 物理实例.status=OFFLINE OR poolPath 为空/无效`，不写入 `storage_pool.status`，与 ACTIVE/MIGRATING/MIGRATED 共存（迁移中且物理机离线则同时展示两标识）。

**理由**：离线是宿主/路径相关的瞬态，持久化会与迁移生命周期状态机冲突；推导反映真实可用性且无需状态迁移逻辑。

### D2: `--gpus all` 显式化（受控端已硬编码生效）

**选择**：受控端 `handleContainerCreate` 已硬编码 `--gpus all`（`DeviceRequests{Driver:"nvidia",Count:-1}`），GPU 透传已生效，**无需功能修复**。本变更仅将管理端 `buildDockerRunPayload` 显式携带 `gpus` 字段（值 `"all"`）并对齐受控端读取，以便审计与命令可见性。

**理由**：原疑虑"缺 --gpus all"经受控端代码确认不成立。显式化使生成命令可审计、与 cpu/memory/shm 一致；一机一卡全卡透传合理，GPU 显存仍为软限制 env（D13 不变）。

### D3: 容器状态实时来源 = 心跳

**选择**：心跳携带本机各容器运行状态；管理端每次心跳按回传更新 `container.status`，变更经既有 `container` SSE 事件推送前端。

**理由**：心跳已每 5s 流动，复用避免新通道，且能捕获带外停止（直接 docker stop）。备选：独立 WS 状态查询（更高延迟与代码量）。

### D4: 心跳信息补齐（IP + 容器状态 + GPU 显存入库）

**选择**：受控端已采集结构化 `GPUInfo`（name/memoryTotal/memoryUsed/driverVer）与 gpuUsage/gpuTemp（经 nvidia-smi），需补齐：
- 新增主机 IP 采集（全部地址，当前完全未采集），加入心跳载荷。
- 新增各容器运行状态采集（Docker SDK `docker ps`/`inspect`），加入心跳载荷。
- GPU 结构化字段整合（将 name/memoryTotal/memoryUsed/utilization/temp 收敛为统一结构，便于消费）。

管理端 `MonitoringService` 将 `gpu.memoryTotal/Used` 写入 `monitoring_history.gpu_memory_total/used`（列已存在但从未填充）。

**理由**：item 12 完整性。GPU 采集方式经确认为 `nvidia-smi`（非 nvml），受控端已具备（Toolkit 依赖）；结构化 GPU 显存同时支撑资源上限默认（memoryTotal）与监控趋势。IP 与容器状态为全新采集。

### D5: 用户/课题组资源配额模型

**选择**：新增 `resource_quota` 表（`scope: USER|GROUP`、`owner_id`、`max_cpu_cores`、`max_memory_mb`、`max_gpu_memory_mb`、`max_shm_mb`，均可空=不限）。有效配额 = 用户配额（若设）-> 课题组配额（若设）-> 目标物理机总容量（来自心跳 memoryTotal/GPU memoryTotal）。配额为**每容器可配置上限**（后端校验 + 表单默认/范围），非聚合预算。

**理由**：用户选择配额制（而非主机总量默认）。每容器上限最简且与软共享一致（D9/D13 接受超额软共享）。此决策更新 D9（原"仅机器维度配额"）与 D13（增加配额软上限，不改硬隔离现状）。

**备选**：聚合预算（跨容器总量）--更"公平"但需用量追踪与争用处理，列为 Non-Goal。

### D6: 镜像限选管理端已配置镜像

**选择**：容器创建表单镜像改为下拉（来源 `GET /images` 可见镜像：自有/共享/公共库）；后端校验 `imageRef` 必须命中已配置镜像，拒绝自由文本。

**理由**：平台无镜像 pull（D3 tar 方案），自由文本无法落地且引入安全风险。限选已配置镜像与分发流程（D8）闭环。

### D7: 镜像应用端口与 tar 自动解析

**选择**：`image_metadata` 增 `app_ports`（JSONB `int[]`）。`upload-tar` 时后端读取 tar 的 `manifest.json` -> 镜像 config JSON -> `RepoTags` 与 `config.ExposedPorts`，据此预填 name/tag 并存 `app_ports`。前端上传改为 `a-upload-dragger`（拖拽，multipart 不变）。容器创建时所选镜像的 `app_ports` 预填"容器内端口"（用户可增删，SSH:22 始终含）。

**理由**：解析在后端（持 tar）更可靠且元数据持久化复用；前端拖拽改善体验。`app_ports` 多个以适配多端口镜像。

### D8: 创建容器时镜像 tar 分发（复用受控端既有 image.load）

**选择**：复用受控端既有 `image.load` 命令处理（已实现 `ImageExists` 跳过 + 下载 + `docker load`）。容器创建流程：管理端创建容器前先下发 `image.load`（payload 含 `sourcePath`=镜像 tar 路径、`transferId`、`imageRef`）；受控端检查本地是否已持有该镜像，已持有则跳过返回 `already_exists`，否则经 file-transfer 下载 tar 并 `docker load`；成功后再下发 `container.create`。**无需新增表或受控端命令**。

**理由**：实现既有 image-management 规格"按需分发"（受控端侧已就绪，缺口仅在管理端未触发）。复用 file-transfer 断点续传；受控端 `ImageExists` 检查避免重复传大包。备选（已否决）：管理端维护"每实例已同步镜像"表预判--多一张表与同步状态维护，而受控端已有存在性检查，表属冗余。

### D9: 管理员课题组全量管理 + 用户无组管理

**选择**：`GroupController` 增 `DELETE /groups/{id}`；前端 `GroupInfoView` 按角色分流--管理员渲染全量课题组管理（`GET /groups` 列表 + 新建/编辑/删除/查看成员），导师/学生保留 `my()` 视图。用户管理无需课题组（后端 `group_id` 可空已是现状；前端 `UserManageView` 表单已无 group 字段，保持）。

**理由**：item 4。后端用户管理已支持无组；缺口在前端管理员看空的课题组页与缺删除接口。

### D10: 管理员菜单裁剪

**选择**：`BasicLayout` 给"特需工单"(`/tickets`)与"公告"(`/announcements`)菜单项加 `roles:['STUDENT','MENTOR']`（排除 ADMIN）；管理员保留"工单管理"(`/tickets/manage`)与"公告管理"(`/announcements/manage`)。

**理由**：item 8/9。对齐 access-control 规格"角色功能模块划分"（管理员模块=管理版）。后端矩阵不变（ADMIN 全权）。

### D11: 容器列表 IP 列 + 应用端口表

**选择**：容器列表增"IP地址"列 = `physical_instance.ip_address`（join，无新列）。连接信息应用端口改 `a-table`，列"暴露端口"(`hostPort`)/"容器内端口"(`containerPort`)--数据已在 `ConnectionInfo.apps`，仅前端渲染。

**理由**：item 11。后端无需改动（数据已具备），直连模式连接 IP 即物理机 IP。

### D12: tar 存储位置（item 5 文档化，不改）

**选择**：不改路径，在 design 明确：私有镜像 `${nexcompute.storage.image-tar-dir}/{userId}/{name}-{tag}.tar`（默认 `./data/storage/images/...`），公共镜像 `${nexcompute.storage.public-image-dir}/{name}-{tag}.tar`（默认 `./data/storage/public-images/...`），由 `STORAGE_ROOT` 环境变量配置（`application.yml` + `NexcomputeProperties` + `StorageInitializer`）。受控端接收的 tar 为临时文件（`os.TempDir()`），load 后即删，不持久化。

## Risks / Trade-offs

- **[GPU 显存采集]** -> 受控端已用 `nvidia-smi --query-gpu=name,...,memory.total,memory.used,...` 采集结构化 GPU（含显存），经确认可用；采集失败时结构化字段留空降级（monitoring 留空、配额回退主机总量）。
- **[IP 采集新增]** -> 受控端新增主机 IP 采集（枚举网卡地址），需过滤回环/虚拟网卡；多网卡时回传全部由管理端选用。
- **[配额为每容器上限，多容器可累计超额]** -> 与既有软共享一致（D9/D13）；聚合预算为 Non-Goal，文档提示同机自协调。
- **[镜像分发增加创建延迟]** -> 复用断点续传；受控端 `ImageExists` 命中即跳过；大镜像首传慢可接受并提示进度。每次创建多一次 WS 往返 + 本地存在性检查，可接受。
- **[心跳载荷增大（含容器状态）]** -> 容器数有限、5s 间隔可接受；必要时仅上报状态变更。
- **[结构化 GPU 改动心跳载荷，旧受控端不兼容]** -> 需受控端同步升级；新增字段可空以降级兼容；考虑 agentVersion 校验。
- **[管理员隐藏"公告查看"后无法预览发布效果]** -> 可在"公告管理"内提供预览；接受。

## Migration Plan

1. **后端 Flyway（新增迁移，纯加列/加表，可回滚）**：`resource_quota` 表；`image_metadata.app_ports`（JSONB）。（镜像分发复用受控端既有 `image.load`，无需新表。）
2. **后端代码**：`HeartbeatRequest` 结构化 GPU/容器状态/IP 字段 + `MonitoringService` 持久化 GPU 显存 + 心跳更新容器状态触发 SSE；`ContainerService` 加 `gpus` 显式字段 + 创建前下发 `image.load` + 配额校验；`ImageService` tar 解析 + `app_ports`；`StoragePool` 离线计算；`GroupController` delete；`ResourceQuotaService`/控制器；容器创建 `imageRef` 校验。
3. **前端**：菜单裁剪；`GroupInfoView` 管理员分支；`ContainerListView` 表单（存储池状态/资源默认+范围/镜像下拉/端口预填）+ IP 列 + 端口表；`ImageListView` 拖拽 + 应用端口 + 解析展示。
4. **受控端**：心跳新增主机 IP 采集与容器状态采集、整合结构化 GPU；`--gpus all` 已硬编码（可选对齐 payload gpus 字段）；`image.load` 命令已存在（无需新增）。
5. **回滚**：迁移为加性（drop 列/表即可回滚）；`gpus`/配额字段可空，功能可经配置降级关闭。

## Open Questions

- 配额是否需在物理实例状态页展示"已用/剩余"？（本期 Non-Goal，后续可加。）
- 心跳中容器状态是否需做增量上报（仅变更）以降载荷？（容器数有限，本期全量上报，必要时优化。）
