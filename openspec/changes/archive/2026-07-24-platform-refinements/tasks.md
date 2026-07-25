## 1. 数据库迁移（Flyway）

- [x] 1.1 `image_metadata` 增列：`usage_instructions`(TEXT)、`note`(VARCHAR)、`project`(VARCHAR)、`source_worker_id`(VARCHAR)、`visibility`(VARCHAR, 默认 PRIVATE)
- [x] 1.2 `resource_allocation` 增列 `per_container_memory_mb`(INT, nullable)
- [x] 1.3 新增全局 `local_admin_password` 单行配置表（明文存储）
- [x] 1.4 既有 `LocalAdminPasswordHash` 数据迁移/废弃处理（按实例密码表保留或清空）

## 2. 公共镜像同步 EOF 修复（item 1）

- [x] 2.1 后端新增 `GET /api/agent/images/public/list`（置于 `/agent/**`，SecurityConfig permitAll + WebConfig 排除，复用 `imageService.listPublicImages()`，校验 agent token/instanceNumber）
- [x] 2.2 受控端 `handleImageSyncPublic` 改查 `/api/agent/images/public/list`
- [x] 2.3 验证受控端启动同步与定时同步不再报 `解析镜像列表失败: EOF`

## 3. tar 存储宿主机可见（item 2）-- 已撤回

已撤回：保留命名卷 `nexcompute-storage`，不改 bind mount。宿主机直接查看上传 tar 的需求暂不实现（如需可后续 `docker cp` 导出）。原 3.1–3.3 任务取消。

## 4. 上传 tar 解析与使用说明（item 3、12）

- [x] 4.1 后端新增 `POST /api/images/parse-tar`（接收 file，解析 `RepoTags`/`ExposedPorts`，不落盘，返回 name/tag/appPorts，复用 `ImageService` 既有解析逻辑）
- [x] 4.2 `ImageController#uploadTar`/`uploadPublic` 增加 `usageInstructions` 参数；`ImageService.uploadTarImage`/`uploadPublicImage` 存 `usage_instructions`
- [x] 4.3 前端 `ImageListView`：文件 `change` 事件调用 `parse-tar` 回填 `tarForm.name/tag/appPorts`
- [x] 4.4 前端 `ImageListView`：上传表单加"使用说明"字段；列表展示 `usage_instructions`

## 5. 移除 Dockerfile 构建（item 9）

- [x] 5.1 后端删除 `ImageController#buildDockerfile` + `BuildDockerfileRequest`
- [x] 5.2 后端删除 `ImageService.buildFromDockerfile` 及受控端 `image.build` 命令派发
- [x] 5.3 受控端删除 `image.build`（Dockerfile 构建）命令处理
- [x] 5.4 前端 `ImageListView` 删除 dockerfile 表单与"编写 Dockerfile"按钮；`image.ts` 删除 `buildDockerfile`
- [x] 5.5 验证 `sourceContainer='dockerfile-build'` 既有镜像元数据仍可展示与使用

## 6. 容器提交镜像持久化（item 10、11）

- [x] 6.1 后端 `ImageService`：commit 镜像默认 `visibility=PRIVATE`；`listVisible` 不向 mentor 默认展示 commit 镜像
- [x] 6.2 后端 `ImageService`：admin 经 `upload-tar` 上传统一置 `visibility=SHARED_TO_ALL`；`listVisible` 纳入 `SHARED_TO_ALL`
- [x] 6.3 后端新增 `POST /containers/{id}/commit-image`（接收 imageName/imageTag/project/note），建 `UPLOADING` 镜像元数据，下发 `image.commit` 命令（含 project/note/ownerWorkerId）
- [x] 6.4 后端 file-transfer 上传完成回调置 `READY`，失败置 `FAILED`；回传未完成不可用于创建容器
- [x] 6.5 受控端扩展 `handleImageCommit`：接收 project/note/ownerWorkerId，tar 命名 `工号-项目-镜像名-标签-备注-随机串`（各段 sanitize），回传完成才返回成功
- [x] 6.6 后端 `ImageController#share` 扩展支持 `targetWorkerId`（按工号精准查用户）与 `targetGroupId`（共享给课题组全体）
- [x] 6.7 前端 `ContainerListView`：容器行新增"提交镜像持久化"操作与弹窗（镜像名/标签/项目/备注）；project 字段预填容器存储池项目名、用户可改
- [x] 6.8 前端 `ImageListView`：新增共享操作（按工号共享 / 共享给课题组）
- [x] 6.9 验证 commit 镜像默认仅本人+管理员可见、回传完成才可用、命名规范、共享按工号/课题组生效

## 7. 存储池删除（item 5）

- [x] 7.1 后端 `StoragePoolController` 新增 `DELETE /storage-pools/{poolId}`（`@RequirePermission DELETE`）
- [x] 7.2 后端 `StoragePoolService.deletePool`：前置检查无运行容器使用该池，下发受控端删除目录命令，删除元数据
- [x] 7.3 前端 `StoragePoolListView` 增加删除按钮 + 二次确认
- [x] 7.4 验证无依赖可删除、有运行容器依赖被拒

## 8. 资源分配（item 6、8）

- [x] 8.1 后端 `ResourceAllocationController` 新增按课题组分配物理实例端点（管理员）
- [x] 8.2 后端导师分配端点扩展：指定学生可使用物理实例 + 单容器内存上限（`per_container_memory_mb`）
- [x] 8.3 后端 `ContainerService` 容器创建校验：若导师设了单容器内存上限，`memory` 不得超过
- [x] 8.4 前端 `group/allocation` 菜单标题按角色：MENTOR="学生资源分配"、ADMIN="课题组资源分配"
- [x] 8.5 前端 `StudentAllocationView`：mentor 版增物理实例多选 + 单容器内存上限；admin 版渲染课题组维度分配表单
- [x] 8.6 验证管理员按课题组分配、导师按学生+内存上限分配、创建超内存被拒

## 9. 用户与课题组管理合并（item 7）

- [x] 9.1 后端 `UserManagementController` 新增 `PUT /admin/users/{id}/groups`（管理课题组归属）与 `POST /admin/users/{id}/reset-password`
- [x] 9.2 前端 `UserManageView` 编辑用户弹窗：增课题组多选（加入/移除）+ 重置密码
- [x] 9.3 前端移除管理员独立"课题组管理"入口（`GroupInfoView` 仅 mentor/student 可见）
- [x] 9.4 验证编辑用户可管理课题组、重置密码后可登录、admin 无独立课题组管理入口

## 10. SSH 密码链路透出（item 4）

- [x] 10.1 前端创建容器表单 SSH 密码字段加说明文案（"用于容器 SSH 登录，明文存储于容器"）
- [x] 10.2 前端连接信息处展示 SSH 密码重置入口与说明
- [x] 10.3 验证无后端逻辑变更（链路已存在），UI 文案清晰

## 11. 受控端管理员密码统一（item 13）

- [x] 11.1 后端新增 `POST /admin/local-admin-password`（设置全局明文密码，广播 `config.set_admin_password` 至所有已连接受控端）
- [x] 11.2 后端离线受控端上线后经心跳回包推送当前全局密码
- [x] 11.3 受控端 `config`：`LocalAdminPasswordHash` 改为 `LocalAdminPassword`（明文）；接收 `config.set_admin_password` 更新并 persist
- [x] 11.4 受控端 GUI：退出与修改存储池根目录时弹出密码输入框校验（当前缺失，补齐）
- [x] 11.5 前端管理员新增"受控端管理密码"统一设置入口
- [x] 11.6 验证全局密码下发所有受控端、受保护配置弹窗、明文保存

## 12. 验证与测试

- [x] 12.1 后端 `./gradlew test`；受控端 `go test ./...`
- [x] 12.2 端到端：公共镜像同步、tar 上传解析回填、commit 持久化与共享、存储池删除、资源分配、用户管理合并、受控端密码
- [x] 12.3 `openspec validate platform-refinements` 通过
