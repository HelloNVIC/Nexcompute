## 1. 存储池离线与状态展示（storage-pool）

- [x] 1.1 后端：`StoragePool` 查询/DTO 增加离线计算（join `physical_instance.status` + `poolPath` 有效性校验），返回离线派生标识，不写入存储状态
- [x] 1.2 前端：`StoragePoolListView` 状态映射增加"离线"，与 ACTIVE/MIGRATING/MIGRATED 共存展示
- [x] 1.3 前端：`ContainerListView` 创建表单存储池下拉展示各池状态，离线池禁用或标注不可选
- [x] 1.4 测试：物理机离线或路径缺失时池显示离线且不可选；迁移中且离线同时展示两标识

## 2. 容器创建与运行改进（container-lifecycle）

- [x] 2.1 后端：`ContainerService.buildDockerRunPayload` 增加 `gpus` 字段（显式化；受控端已硬编码 `--gpus all` 生效）
- [x] 2.2 受控端：`--gpus all` 已硬编码（`DeviceRequests{Count:-1}`），无需功能改动；可选对齐 payload gpus 字段读取以便审计
- [x] 2.3 后端：容器创建校验 `imageRef` 必须命中管理端已配置镜像，拒绝自由文本
- [x] 2.4 前端：创建表单镜像改为下拉（来源 `GET /images` 可见镜像），移除自由文本输入
- [x] 2.5 前端：资源限制（CPU/内存/GPU显存/SHM）默认填有效配额、显示 `[min, max]` 范围
- [x] 2.6 后端：容器创建校验资源限制不超过有效配额（用户->课题组->主机容量回退）
- [x] 2.7 前端：选择镜像后将其 `app_ports` 预填到"容器内端口"（SSH:22 始终含，可增删）
- [x] 2.8 后端：创建容器前下发 `image.load` 触发按需分发（详见 3.5）

## 3. 镜像管理增强（image-management）

- [x] 3.1 后端 Flyway：`image_metadata` 增 `app_ports`（JSONB `int[]`）
- [x] 3.2 后端：`ImageService.uploadTar` 解析 tar `manifest.json` -> 镜像 config JSON -> `RepoTags`/`ExposedPorts`，预填 name/tag 并存 `app_ports`
- [x] 3.3 后端：`upload-tar` 接口返回解析后的 name/tag/app_ports；镜像列表/详情返回 `app_ports`
- [x] 3.4 前端：`ImageListView` 上传改 `a-upload-dragger`（拖拽），增加"应用端口"字段（多个），展示解析结果
- [x] 3.5 后端：`ContainerService.createContainer` 创建前下发 `image.load`（payload 含 `sourcePath`=镜像 tar 路径、`transferId`、`imageRef`）；受控端既有处理检查本地有无、按需下载并 `docker load`，成功后再 `container.create`（无需新增表或受控端命令）
- [x] 3.6 测试：拖拽上传解析正确；非本地镜像创建容器触发 `image.load`+load（受控端检查已持有则跳过）；分发失败中止创建

## 4. 心跳与实时状态（agent-communication + monitoring）

- [x] 4.1 受控端：心跳新增主机 IP 采集（枚举网卡地址，过滤回环/虚拟网卡）与各容器运行状态采集（Docker SDK `docker ps`/`inspect`）；整合既有结构化 GPU（`GPUInfo` name/memTotal/memUsed/driverVer + gpuUsage/gpuTemp）。GPU 采集经 `nvidia-smi`（已确认，非 nvml）
- [x] 4.2 后端：`HeartbeatRequest` 增结构化 GPU/IP/容器状态字段（保留旧字段兼容）；`MonitoringService` 持久化 `gpu_memory_total`/`gpu_memory_used`
- [x] 4.3 后端：心跳中按 `containers[]` 更新 `container.status`，变更经既有 `container` SSE 事件推送前端
- [x] 4.4 前端：SSE `container` 事件实时更新容器列表状态；物理实例状态展示结构化 GPU（型号/显存）
- [x] 4.5 测试：心跳回传 IP/GPU/容器状态；容器外部停止后状态实时更新；GPU 显存入库与历史趋势展示；无 GPU 时降级留空

## 5. 管理员与权限/配额（access-control + resource-allocation）

- [x] 5.1 后端 Flyway：`resource_quota` 表（`scope` USER|GROUP、`owner_id`、`max_cpu_cores`/`max_memory_mb`/`max_gpu_memory_mb`/`max_shm_mb`，可空=不限）
- [x] 5.2 后端：`ResourceQuotaService` + 控制器（管理员 CRUD 用户/课题组配额）；有效配额计算（用户->课题组->主机容量）
- [x] 5.3 后端：`GroupController` 增 `DELETE /groups/{id}`
- [x] 5.4 前端：`GroupInfoView` 管理员分支（全量课题组列表 `GET /groups` + 新建/编辑/删除/查看成员）；导师/学生保留 `my()` 视图
- [x] 5.5 前端：`BasicLayout` 给"特需工单"(`/tickets`)与"公告"(`/announcements`)加 `roles:['STUDENT','MENTOR']`，管理员保留"工单管理"/"公告管理"
- [x] 5.6 测试：管理员管理全部课题组与所有用户（无需组）；配额默认/上限生效；管理员菜单不显示特需工单/公告

## 6. 容器配置展示（container-lifecycle）

- [x] 6.1 前端：容器列表增"IP地址"列（物理机 IP，join `physical_instance.ip_address`）
- [x] 6.2 前端：连接信息应用端口改 `a-table`，列"暴露端口"(`hostPort`)/"容器内端口"(`containerPort`)
- [x] 6.3 测试：IP 列与端口表正确展示（数据源自既有 `ConnectionInfo.apps`）

## 7. 集成与测试

- [x] 7.1 端到端：创建容器全流程（`--gpus all` 已生效 + 配额默认/范围 + 镜像限选 + 端口预填 + 非本地镜像 `image.load` 分发）
- [x] 7.2 端到端：心跳回传 IP/GPU/容器状态 + 容器状态实时更新 + GPU 显存监控趋势
- [x] 7.3 端到端：存储池离线（物理机离线/路径缺失）+ 创建表单状态展示与禁选
- [x] 7.4 端到端：管理员全量课题组管理 + 用户无组管理 + 菜单裁剪
- [x] 7.5 端到端：镜像拖拽上传 + tar 解析 + 应用端口预填到容器创建
- [x] 7.6 回归：既有容器/存储池/镜像/心跳/权限矩阵/通知功能不受影响
- [x] 7.7 文档：在 design/README 明确镜像 tar 存储位置（item 5）
