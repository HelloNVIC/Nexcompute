## Context

`platform-improvements` 已落地，本轮 13 项为投用后暴露的缺口，横跨镜像管理、资源分配、受控端运维三块。关键现状（经受控端/后端代码确认）：

- 受控端 `handleImageSyncPublic`（`image_handlers.go:114`）以 agent token 请求 `/api/images/public/list`；该端点 `ImageController#publicList`（`ImageController.java:59`）仅受 Spring Security JWT + `permissionInterceptor`（`WebConfig.java:35` 仅排除 `/auth/** /agent/** /ping /actuator /error`）保护，故受控端请求被拦截、响应体为空，`json.Decode` 返回 `EOF`。对照 `HeartbeatController`、`FileTransferController`（`@RequestMapping("/agent/file")`）均在 SecurityConfig `permitAll` 且经 agent token 校验。
- 上传 tar 时后端已解析 `RepoTags`/`ExposedPorts`（`ImageController#uploadTar`、`ImageService.uploadTarImage`），但解析在提交后进行，未在选定时回填表单。
- SSH 密码：用户创建容器时填写（`ContainerCreateRequest.sshPassword`），明文存 `Container.sshPassword`，下发 payload（`ContainerService:121/409`），运行中可 `container.reset_ssh` 即时重置（`resetSshPassword`，`ContainerService:292`）。链路已存在，仅缺 UI 透出。
- 存储池 `StoragePoolController` 仅有 list/create/share/revoke/migrate，无 delete。
- 镜像管理存在 Dockerfile 构建能力（`ImageController#buildDockerfile`、`BuildDockerfileRequest`、`ImageService.buildFromDockerfile`、`ImageListView` dockerfile 表单、`imageApi.buildDockerfile`）。
- 容器 commit 镜像能力已存在（`handleImageCommit`：commit+save+上传），但无项目/备注、无命名规范、未约束"回传完成才成功"、可见性沿用 mentor 默认可见。
- 受控端本地管理员密码按实例下发（`LocalAdminPasswordController` `/admin/instances/{instanceId}/local-admin-password`），`config.LocalAdminPasswordHash` 哈希存储；GUI 未对受保护配置弹出密码提示。
- 资源分配现状：导师按学生分配物理实例（`ResourceAllocationController`），无单容器内存上限约束；管理员菜单亦显示"学生资源分配"。

## Goals / Non-Goals

**Goals:**
- 修复公共镜像同步 EOF，使公共镜像可分发。
- 上传即解析回填、补"使用说明"字段。
- 容器一键 commit 持久化为可复用镜像，命名规范、回传完成才成功、默认私有可显式共享。
- 管理员上传镜像全用户可见；移除 Dockerfile 构建。
- 存储池可删除；管理员按课题组、导师按学生+单容器内存分配资源。
- 用户与课题组管理合并，编辑用户可管理课题组与重置密码。
- 受控端管理员密码全局共享、明文，受保护配置弹密码提示。

**Non-Goals:**
- 不改公共镜像库自动同步机制（仅修复可达性）。
- 不引入镜像 pull/registry（仍为 tar 分发）。
- 不做存储池软删除/历史审计（直接删除）。
- 不对受控端管理密码做哈希或加密传输（用户明确要求明文）。

## Decisions

### D1. 公共镜像同步 EOF 修复（item 1）
- **Decision**：新增受控端专用端点 `GET /api/agent/images/public/list`，置于 `/agent/**`（SecurityConfig `permitAll` + `WebConfig` 排除），service 内校验 agent token/instanceNumber，复用 `imageService.listPublicImages()`。受控端 `handleImageSyncPublic` 改查该端点。tar 下载复用既有 `/agent/file/**`（已 agent 鉴权），无需改动。
- **Alternatives**：(a) 将 `/images/public/list` 直接 `permitAll` 并排除权限拦截器--rejected，混淆前端 JWT 访问与受控端 agent 访问、绕过权限矩阵；(b) 受控端改用 JWT--rejected，受控端无用户身份。
- **Rationale**：与既有 `HeartbeatController`/`FileTransferController` 模式一致（均在 `/agent/**` 下 agent-token 鉴权）。

### D2. tar 存储宿主机可见（item 2）-- 已撤回
- **Decision**：撤回。保留命名卷 `nexcompute-storage`，不改 bind mount。宿主机直接查看上传 tar 的需求暂不实现（如需可后续 `docker cp` 导出或重建命名卷）。

### D3. 上传 tar 选定即解析回填（item 3）
- **Decision**：新增 `POST /api/images/parse-tar`（接收 file，解析 `RepoTags`/`ExposedPorts`，不落盘，返回 name/tag/appPorts）。前端 `ImageListView` 在文件 `change` 事件调用并回填 `tarForm.name/tag/appPorts`，用户可改后提交 `upload-tar`。后端解析复用 `ImageService` 既有逻辑。
- **Alternatives**：(a) 纯前端 JS 解析 tar manifest--rejected，浏览器解析复杂、重复后端逻辑；(b) 维持上传后解析--rejected，不满足"填入文本框"诉求。

### D4. SSH 密码链路透出（item 4）
- **Decision**：无后端逻辑变更（链路已存在）。前端创建容器表单 SSH 密码字段加说明文案（"用于容器 SSH 登录，明文存储于容器"），连接信息处展示重置入口与说明。
- **Rationale**：用户疑问源于链路不透明，文档化 + UI 提示即可解决。

### D5. 存储池删除（item 5）
- **Decision**：`StoragePoolController` 新增 `DELETE /storage-pools/{poolId}`（`@RequirePermission DELETE`）；`StoragePoolService.deletePool` 前置检查无运行容器使用该池（复用 `revokeShare` 依赖检查逻辑），通过后下发受控端删除目录命令并删除元数据。前端加删除按钮 + 二次确认。
- **Alternatives**：软删除（标记 DELETED）--rejected，无历史审计需求。
- **Risk**：误删数据 -> 删除前二次确认 + 运行容器前置检查。

### D6. 管理员"课题组资源分配"（item 6）
- **Decision**：路由 `group/allocation` 菜单标题按角色：MENTOR="学生资源分配"、ADMIN="课题组资源分配"。视图按角色渲染不同分配表单（admin：选课题组 + 选物理实例；mentor：见 D8）。后端 `ResourceAllocationController` 新增按课题组分配端点。
- **Alternatives**：拆两个路由--rejected，复用同一路由按角色切换更简洁。

### D7. 合并用户与课题组管理（item 7）
- **Decision**：移除管理员独立"课题组管理"入口（`GroupInfoView` 仅 mentor/student 可见，admin 不再单独显示）。`UserManageView` 编辑用户弹窗新增：课题组多选（加入/移除）+ 重置密码。后端 `UserManagementController` 新增 `PUT /admin/users/{id}/groups` 与 `POST /admin/users/{id}/reset-password`。
- **Rationale**：用户明确要求合并；编辑用户即管理其课题组归属与凭证，信息集中。
- **Note**：mentor/student 的"课题组信息"（只读/可编辑）保留，仅移除 admin 独立入口。

### D8. 导师学生资源分配完善（item 8）
- **Decision**：mentor 的 `StudentAllocationView` 增：为每学生选可使用物理实例（多选）+ 单容器内存上限（MB）。`resource_allocation` 表 Flyway 增 `per_container_memory_mb` 列。容器创建校验：若导师设了该上限，`ContainerCreateRequest.memory` 不得超过，复用 `ContainerService` 既有资源限制校验路径。
- **Rationale**：与 `platform-improvements` 的用户/课题组总配额（ResourceQuota）互补--总配额管总量，本项管单容器上限。
- **Alternatives**：仅用总配额--rejected，无法精细约束单容器内存。

### D9. 移除 Dockerfile 构建（item 9）
- **Decision**：删除 `ImageController#buildDockerfile` + `BuildDockerfileRequest`、`ImageService.buildFromDockerfile`、前端 `ImageListView` dockerfile 表单与 `imageApi.buildDockerfile`、受控端 `image.build` 命令处理。镜像来源仅保留 tar 上传与容器 commit。
- **Migration**：已构建镜像（`sourceContainer='dockerfile-build'`）保留元数据，仅移除构建能力。
- **Rationale**：用户明确要求去掉；维护负担与诉求不符。

### D10. 容器提交镜像持久化（item 10）
- **Decision**：
  - 后端 `POST /containers/{id}/commit-image`（接收 imageName/imageTag/project/note），下发 `image.commit` 命令（含 project/note/ownerWorkerId）至受控端；镜像元数据先建为 `UPLOADING`，受控端 commit+save 后经 file-transfer 上传 tar，上传完成回调置 `READY`，失败置 `FAILED`。
  - 项目字段预填：前端 commit 弹窗的 project 默认取该容器所挂载存储池的项目名（命名格式 `物理机编号-工号-项目名` 中的用户自定义段），用户可修改后再提交。
  - 受控端扩展 `handleImageCommit`：接收 project/note/ownerWorkerId；tar 文件名 `工号-项目-镜像名-标签-备注-随机串`（各段 sanitize 去路径分隔符与非法字符，随机串取 UUID 短串）；回传完成才返回成功。
  - 可见性：commit 镜像默认 `visibility=PRIVATE`（仅 owner+admin），`listVisible` 不向 mentor 默认展示。
  - 共享：`ImageController#share` 扩展支持 `targetWorkerId`（按工号精准查用户）与 `targetGroupId`（共享给课题组全体），复用 `ImageShare` 表。
- **Alternatives**：tar 文件名用纯 UUID--rejected，用户明确要求可读命名；随机串避免重名。
- **Risk**：命名含中文/特殊字符 -> sanitize；回传中断 -> 状态保持 UPLOADING/FAILED，可重试或清理。

### D11. 管理员上传镜像全用户可见（item 11）
- **Decision**：`ImageMetadata` 新增 `visibility` 列（`PRIVATE`/`SHARED_TO_ALL`/`SHARED`）。admin 经 `upload-tar` 上传统一置 `SHARED_TO_ALL`；用户 commit/upload 默认 `PRIVATE`，共享后置 `SHARED`。公共镜像库（`uploadPublic`，`isPublic=true`）语义不变（自动同步）。`listVisible` 纳入 `SHARED_TO_ALL`。
- **不自动同步**：admin 上传的 `SHARED_TO_ALL` 镜像不进公共镜像库、不自动同步至受控端；受控端仅在使用该镜像创建容器时按既有 `image.load` 按需下载 tar 并 `docker load`。
- **Alternatives**：复用 `isPublic`--rejected，`isPublic` 走公共镜像库自动同步语义，与"全用户可见但不自动同步"不同。
- **Rationale**：区分三类可见性（公共库/全用户可见/私有共享）避免语义混淆。

### D12. 使用说明字段（item 12）
- **Decision**：`ImageMetadata` 增 `usage_instructions`（TEXT, nullable）；`upload-tar`/`upload-public` 增加 `usageInstructions` 参数；`ImageListView` 表单加字段 + 列表展示。
- **Flyway**：`image_metadata` 一次性增 `usage_instructions`、`note`、`project`、`source_worker_id`、`visibility` 列。

### D13. 受控端管理员密码统一（item 13）
- **Decision**：
  - 后端：改为全局密码。新增全局单条记录 `local_admin_password`（明文）；`POST /admin/local-admin-password`（设置全局密码）-> 广播 `config.set_admin_password` 命令至所有已连接受控端；废弃/删除按实例端点。
  - 受控端：`config.LocalAdminPasswordHash` -> `LocalAdminPassword`（明文）；接收 `config.set_admin_password` 命令更新并 persist；GUI 退出与修改存储池根目录时弹出密码输入框校验（当前缺失，补齐）。
  - 前端：管理员新增"受控端管理密码"统一设置入口。
  - 离线受控端：上线后经心跳回包（heartbeat response）推送当前全局密码（非握手阶段）。
- **Alternatives**：(a) 继续按实例--rejected，用户要求统一共用；(b) 哈希保存--rejected，用户明确要求明文。
- **Risk**：明文密码泄露 -> 用户明确接受；管理端仅管理员可访问该设置。

## Risks / Trade-offs

- [受控端管理密码明文传输/保存] -> 用户明确要求并接受； mitigations：仅管理员可设/读、WS 通道已鉴权。
- [commit 镜像默认不向导师可见（BREAKING）] -> 导师需学生显式共享； mitigations：共享入口便捷（按工号/课题组）。
- [管理员上传镜像全用户可见（BREAKING）] -> 用户上传的私有镜像不受影响（仅 admin 上传置 SHARED_TO_ALL）。
- [回传中断致镜像卡 UPLOADING] -> 提供重试/清理入口（后续可加定时清理）。
- [命名 sanitize 过度致可读性下降] -> 仅去路径分隔符与控制字符，保留中文与常规符号。

## Migration Plan

1. Flyway 迁移：`image_metadata` 增列（usage_instructions/note/project/source_worker_id/visibility）→ `resource_allocation` 增 `per_container_memory_mb` → `local_admin_password` 全局表。
2. 后端部署：新增端点（`/agent/images/public/list`、`/images/parse-tar`、`/containers/{id}/commit-image`、`DELETE /storage-pools/{id}`、用户课题组/重置密码、全局密码），移除 `/images/build-dockerfile`，兼容旧受控端连接。
3. 前端部署：菜单/视图调整、镜像/容器/存储池/用户管理 UI。
4. 受控端升级：密码明文化 + 受保护配置弹窗 + commit 命名扩展；升级后由管理端重新下发全局密码。
5. `docker-compose`：无卷变更（保留命名卷 `nexcompute-storage`）；如需宿主机查看 tar 可后续 `docker cp` 导出。
6. 回滚：密码明文升级后哈希字段废弃（不可逆，需重新设置）。tar 存储仍为命名卷，无卷变更需回滚。

## Open Questions

无未决问题。已确认：
- **D10 项目字段**：自动取容器存储池项目名预填，用户可修改。
- **D11 管理员上传镜像**：不自动同步至受控端，仅在使用时按需 `docker load`。
- **D13 离线受控端密码**：经心跳回包推送当前全局密码。
