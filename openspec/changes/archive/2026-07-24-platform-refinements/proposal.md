## Why

平台二轮迭代（`platform-improvements`）已落地，实际投用中又暴露出一批影响镜像分发、资源分配与受控端运维的缺口：受控端公共镜像同步因管理端鉴权配置失败（`EOF`）、上传 tar 未即时解析回填表单、SSH 密码设置链路对用户不透明、存储池无法删除、管理员与导师的资源分配菜单错位且分裂、镜像管理中的 Dockerfile 构建功能冗余、容器无法一键 commit 持久化为可复用镜像、管理员上传镜像未对所有用户可见、上传 tar 缺"使用说明"字段、受控端本地管理员密码按实例下发且受保护配置无密码输入提示。本变更批量修复并完善，使镜像与资源分配流程真正闭环可用。

## What Changes

**镜像同步与存储**
- 修复受控端公共镜像同步 `EOF`：`/api/images/public/list`（`ImageController#publicList`）当前仅受 JWT 拦截器保护，受控端以 agent token 请求被拦截、响应体为空，致 `json.Decode` 返回 `EOF`。将公共镜像列表与同步所需 tar 下载路径纳入 agent-token 鉴权白名单。

**镜像上传与解析**
- 上传 tar 选定文件后即时解析（`RepoTags`/`ExposedPorts`）并回填名称/标签/应用端口文本框，提交时复用解析值。
- 上传 tar 新增"使用说明"字段，存入镜像元数据并在镜像列表展示。
- 去掉镜像管理中的"Dockerfile 编写/构建"功能（前端表单、后端 `/images/build-dockerfile` 接口与 `BuildDockerfileRequest`、`ImageService.buildFromDockerfile`、受控端 build 命令处理一并移除）。
- **BREAKING（行为）**：管理员通过 `upload-tar` 上传的镜像对所有用户可见（不再仅管理员可见）。

**容器提交镜像持久化**
- "容器配置"每个容器新增"提交镜像持久化"操作：弹窗输入镜像名/标签/项目/备注，受控端 `docker commit` + `docker save` 导出 tar 并回传管理端；tar 文件命名 `工号-项目-镜像名-标签-备注-随机串`；回传完成方记为持久化成功。
- 提交产生的镜像默认仅本人与管理员可见（**BREAKING**：不再对导师默认可见，改为显式共享）。
- 镜像管理新增共享：可按工号精准匹配共享给指定用户，或直接共享给整个课题组。

**SSH 密码**
- 明确并透出 SSH 密码设置链路：用户创建容器时填写，明文存于容器实体并下发受控端，运行中可即时重置（`container.reset_ssh`）；前端创建表单与连接信息增加说明文案。

**存储池**
- 存储池支持删除（前置检查无运行容器依赖），新增 `DELETE /storage-pools/{poolId}`。

**资源分配与权限**
- 管理员菜单"学生资源分配"改为"课题组资源分配"：以课题组为单位分配物理实例。
- 导师后台"学生资源分配"完善：可指定学生可用物理实例、单容器内存上限。
- "用户与课题组管理"与"课题组管理"合并为单一"用户与课题组管理"：编辑用户弹窗内可管理其所在课题组（加入/移除）、可重置密码。

**受控端管理员密码**
- 受控端本地管理员密码改为全局共享（所有受控端共用一个），明文传输明文保存；管理端新增统一设置入口（替代按实例下发）。
- 受控端修改受保护配置（存储池根目录）与退出时正确弹出密码输入提示（当前缺失）。

## Capabilities

### New Capabilities

（无——均为对既有能力的修改与扩展）

### Modified Capabilities

- `image-management`: 公共镜像同步列表/tar 路径对受控端 agent-token 可达（修复 `EOF`）；上传 tar 选定即解析回填表单；新增"使用说明"字段；移除 Dockerfile 构建能力；容器提交镜像持久化（命名规范、回传完成才成功、默认仅本人与管理员可见）；镜像共享支持按工号精准匹配与共享给课题组；管理员上传镜像对所有用户可见。
- `resource-allocation`: 管理员以课题组为单位分配物理实例（"课题组资源分配"）；导师可指定学生可用物理实例与单容器内存上限。
- `storage-pool`: 新增存储池删除（前置检查无运行容器依赖）。
- `access-control`: "用户与课题组管理"与"课题组管理"合并为单一"用户与课题组管理"；编辑用户可管理其所在课题组并重置密码。
- `controlled-agent`: 本地管理员密码改为全局共享、明文传输与保存；受保护配置修改与退出正确弹出密码输入提示。

## Impact

**管理端后端（SpringBoot）**
- `WebConfig`/安全配置：将 `/images/public/**`（及同步所用 file-transfer 下载路径）纳入 agent-token 鉴权白名单，修复受控端同步 `EOF`。
- `ImageController`：移除 `/build-dockerfile` 与 `BuildDockerfileRequest`；`uploadTar`/`uploadPublic` 增加 `usageInstructions` 参数；新增 `POST /images/parse-tar`（选定即解析，不落盘）；新增 `POST /containers/{id}/commit-image`（提交镜像持久化）；共享接口支持按工号查询目标用户与共享给课题组。
- `ImageMetadata`：新增 `usage_instructions`、`note`、`project`、`source_worker_id` 列；可见性规则调整（管理员上传镜像全可见；commit 镜像默认仅本人+管理员）。
- `ImageService`：移除 `buildFromDockerfile`；`listVisible` 纳入管理员上传镜像与共享规则；新增 commit-image 流程（受控端 commit+save+回传，回传完成置 READY）。
- `StoragePoolController`/`StoragePoolService`：新增 `DELETE /storage-pools/{poolId}`（前置检查无运行容器依赖）。
- `ResourceAllocationController`/`ResourceAllocationService`：管理员按课题组分配物理实例；导师按学生分配物理实例 + 单容器内存上限。
- `UserManagementController`：编辑用户管理课题组（加入/移除）+ 重置密码。
- `LocalAdminPasswordController`/`LocalAdminPasswordService`：改为全局共享密码（单一设置，下发所有受控端），明文存储。
- Flyway 迁移：`image_metadata` 增列、`resource_allocation` 增 `per_container_memory`、`local_admin_password` 全局表（或配置项）。

**管理端前端（Vue）**
- 路由/菜单：管理员"学生资源分配"→"课题组资源分配"；移除独立"课题组管理"入口并入"用户与课题组管理"。
- `ImageListView`：移除 Dockerfile 表单；上传 tar 选定即解析回填；新增"使用说明"字段与展示；新增 commit/共享操作。
- `ContainerListView`：容器行新增"提交镜像持久化"操作与弹窗；SSH 密码说明文案。
- `StudentAllocationView`：导师版增物理实例选择 + 单容器内存上限；管理员版改为课题组维度分配。
- `UserManageView`：编辑用户弹窗增加课题组管理与重置密码。
- `StoragePoolListView`：增加删除按钮。
- 新增"受控端管理密码"统一设置入口（管理员）。

**受控端（Go）**
- `handleImageCommit`：扩展接收项目/备注，按命名规范生成 tar 文件名，回传完成才算成功。
- 移除 `image.build`（Dockerfile 构建）命令处理。
- `config`：`LocalAdminPasswordHash` 改为 `LocalAdminPassword`（明文）；接收全局密码下发。
- GUI：修改存储池根目录与退出时弹出密码输入提示（当前缺失）。
- 公共镜像同步：随管理端鉴权修复恢复（受控端无需改动或仅对齐 header）。

**基础设施**
- 无 docker-compose 卷变更（保留命名卷 `nexcompute-storage`）。

**安全面**
- 受控端管理密码明文传输与保存（用户明确要求，接受该风险）；镜像可见性收紧（commit 镜像默认不向导师开放，需显式共享）。
