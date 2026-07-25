## Why

平台首版（`build-nexcompute-platform`）已落地，但在实际使用中暴露出一批影响可用性与运维体验的缺口：容器运行状态不实时（仅靠生命周期动作结果写入、外部停止后状态陈旧）、心跳未回传主机 IP 与容器状态、结构化 GPU 显存虽经 nvidia-smi 采集但未入库、存储池无"离线"标识（物理机离线或路径无效时无法识别）、镜像管理粗糙（上传无拖拽、无应用端口、不解析 tar、创建容器时管理端未触发既有 `image.load` 分发）、管理员菜单冗余（同时显示学生版工单/公告）、课题组信息对管理员空白（仅加载"我的课题组"）、资源限制无默认也无配额约束。本变更批量修复这些问题，使平台达到可实际投用的状态。

> 勘误（经受控端代码确认）：item 2 的 `--gpus all` 并非缺失--受控端 `handleContainerCreate` 已硬编码 `DeviceRequests{Driver:"nvidia",Count:-1}`（等价 `--gpus all`），容器 GPU 透传实际已生效。本变更对 item 2 仅做"管理端 payload 显式化"以便审计。受控端 `image.load` 命令处理亦已存在（含 `ImageExists` 跳过、下载、`docker load`），item 10 的缺口仅在管理端未于创建容器前触发它。

## What Changes

**存储池与状态**
- 存储池新增"离线"状态（计算型，不新增存储枚举）：当其物理机离线，或 `poolPath` 缺失/无效时显示为离线，不与 ACTIVE/MIGRATING/MIGRATED 冲突。
- 容器创建表单的存储池下拉展示各池状态（含离线），便于用户避开不可用池。

**容器创建与运行**
- `--gpus all` 显式化：受控端已硬编码 `--gpus all` 生效，本变更将管理端 payload 显式携带该指令以便审计（GPU 显存仍为软限制 env 注入，非硬隔离）。
- 资源限制（CPU/内存/GPU显存/SHM）默认填"当前用户可使用最大值"并显示数值范围 `[min, max]`；该"最大值"取用户/课题组资源配额（见下"用户资源配额"），无配额时回退目标物理机总容量。
- 镜像只能从管理端已配置的镜像中选择（下拉），不再允许自由文本输入。
- 容器内端口由所选镜像的"应用端口"自动预填，用户可增删，SSH:22 始终包含。
- 创建容器时，若目标受控端本地无该镜像，管理端先传输 tar 并 `docker load` 导入，再创建容器（实现既有 image-management 规格"按需分发"要求）。

**镜像管理**
- 增加镜像时支持拖拽上传 tar；新增"应用端口"字段（支持多个端口）。
- 上传时后端自动解析 tar 的 `RepoTags` 与 `ExposedPorts`，预填名称/标签并存储应用端口。

**心跳与实时状态**
- 心跳完整回传：主机 IP（全部地址）、结构化 GPU 信息（型号、总显存、已用显存、利用率、温度）、本机各容器运行状态。
- 受控端在心跳中携带容器运行状态；管理端据此实时更新容器状态并经 SSE 推送前端。
- 结构化 GPU 显存写入 `monitoring_history`（`gpu_memory_total`/`gpu_memory_used` 列已存在但从未填充）。

**管理员与权限**
- 课题组信息改为管理员可管理全部课题组（列表/新建/编辑/删除/查看成员），不再仅显示"我的课题组"。
- 管理员可管理所有学生与用户，无需关联课题组（`user.group_id` 保持可空，已是后端现状，前端补齐）。
- 新增用户/课题组资源配额（CPU/内存/GPU显存/SHM 上限），由管理员设置，作为容器创建默认值与上限校验。**BREAKING（行为）**：创建容器时若资源限制超过配额将被拒绝。
- 管理员菜单不再显示"特需工单"模块（仅保留"工单管理"）。
- 管理员菜单不再显示"公告"模块（仅保留"公告管理"）。

**容器配置展示**
- 容器列表新增"IP地址"列（物理机 IP，即直连连接 host）。
- 连接信息中的应用端口以表格展示，字段为"暴露端口"（宿主端口）与"容器内端口"（后端 `ConnectionInfo.apps` 已带 `hostPort`/`containerPort`，前端补表格渲染）。

## Capabilities

### New Capabilities

（无——均为对既有能力的修改与扩展）

### Modified Capabilities

- `storage-pool`: 新增"离线"计算状态；容器创建时存储池选择展示各池状态。
- `container-lifecycle`: 创建命令注入 `--gpus all`；资源限制按用户配额默认填充并显示范围；镜像限选管理端已配置镜像；容器内端口由镜像应用端口预填；创建时按需分发 tar 并 `docker load`；容器列表新增 IP 列；连接信息应用端口以表格（暴露端口/容器内端口）展示；容器状态以心跳为实时来源。
- `agent-communication`: 心跳完整回传主机 IP 与结构化 GPU（型号/总显存/已用显存/利用率/温度）及各容器运行状态；容器状态经心跳实时同步并 SSE 推送。
- `image-management`: 镜像限选管理端已配置镜像；拖拽上传 tar；新增应用端口（多个）；上传自动解析 `RepoTags` 与 `ExposedPorts`；创建容器时按需分发 tar 并 `docker load`。
- `access-control`: 管理员管理全部课题组与所有用户（无需关联课题组）并设置资源配额；管理员菜单移除学生版"特需工单"与"公告"模块。
- `resource-allocation`: 课题组信息由管理员全量管理；新增用户/课题组资源配额（CPU/内存/GPU显存/SHM 上限）。
- `monitoring`: 结构化 GPU 显存持久化入库；容器运行状态实时 SSE 推送。

## Impact

**管理端后端（SpringBoot）**
- Flyway 新增迁移：`resource_quota`（用户/课题组配额）表、`image_metadata` 增 `app_ports`（JSONB）列；`container` 无需新列（IP 复用 `physical_instance.ip_address`）。
- `HeartbeatRequest` 扩展结构化 GPU + 容器状态字段；`MonitoringService` 持久化 `gpu_memory_total`/`used`；容器状态以心跳更新并触发 `container` SSE 推送。
- `ContainerService.buildDockerRunPayload` 增加 `gpus` 字段（显式化，受控端已硬编码 --gpus all）；创建容器前下发 `image.load`（受控端检查本地有无、按需下载并 `docker load`）。
- `ImageService` 解析 tar（`RepoTags`/`ExposedPorts`）并存 `app_ports`；`ImageController.uploadTar` 复用拖拽上传。
- `StoragePool` 查询增加离线计算（join `physical_instance.status` + `poolPath` 有效性）。
- `GroupController` 增加 `DELETE`；新增 `ResourceQuotaService`/控制器；用户管理无 group 现状保持。
- 镜像创建校验：`imageRef` 必须命中管理端已配置镜像。

**管理端前端（Vue）**
- `BasicLayout` 菜单按角色隐藏"特需工单"/"公告"（限 STUDENT/MENTOR）。
- `GroupInfoView` 管理员改为全量课题组管理视图。
- `ContainerListView` 创建表单：存储池下拉带状态、资源限制默认+范围、镜像下拉（仅已配置）、容器内端口预填；列表新增 IP 列；连接信息端口表格。
- `ImageListView` 上传改为拖拽、增加应用端口、展示解析结果。
- 存储池列表/物理实例状态展示离线。

**受控端（Go）**
- 心跳结构增加主机 IP 列表（当前完全未采集）、各容器运行状态；结构化 GPU（型号/总显存/已用/利用率/温度，经 nvidia-smi，受控端已采集 name/memTotal/memUsed/driverVer 与 gpuUsage/gpuTemp，需补齐整合）；容器状态采集（`docker ps`/`inspect`）纳入心跳。
- `--gpus all` 已硬编码生效（`DeviceRequests{Count:-1}`），无需改动；可选对齐管理端 payload 的 gpus 字段以便审计。
- `image.load` 命令处理已存在（`ImageExists` 跳过、下载、`docker load`），无需新增；管理端创建容器前下发 `image.load` 触发之。

**基础设施**
- 无新外部依赖；GPU 采集依赖受控端既有 NVIDIA 工具链。

**安全面**
- 镜像限选管理端已配置镜像（防自由文本拉取任意镜像）；tar 分发复用既有 file-transfer 鉴权与校验。
