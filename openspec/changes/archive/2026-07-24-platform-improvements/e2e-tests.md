# platform-improvements 端到端与前端展示测试场景

本变更为批量修复与增强。后端核心逻辑由 JUnit 单元测试覆盖（见 `management-backend/src/test`），
端到端流程需在完整栈（PostgreSQL + Redis + 管理端 + 受控端 + Docker）运行下按以下场景手动验证。

> 前端无自动化测试框架（package.json 仅有 `type-check`），故前端展示类任务（3.6/4.5/5.6/6.3 的前端部分）
> 以以下场景手工验证；后端单元测试覆盖对应数据与校验逻辑。

## 已有单元测试覆盖

| 任务 | 测试 | 文件 |
|------|------|------|
| 1.4 | 存储池离线计算（物理机离线/路径缺失/迁移中+离线共存） | `StoragePoolServiceTest` |
| 2.3/3.6 | imageRef 必须命中已配置镜像；tar 解析失败降级 | `ImageServiceTest` |
| 4.5 | 心跳回传容器状态实时更新 + SSE 推送 | `HeartbeatServiceTest` |
| 5.6 | 有效配额回退（用户->课题组->主机容量）；课题组删除清理 | `ResourceQuotaServiceTest`、`ResearchGroupServiceTest` |
| 4.1 | 受控端结构化 GPU/IP 采集结构 | `sysinfo_test.go`（`TestGPUInfo_Structure` 等） |

## 7.1 端到端：创建容器全流程

1. 管理员为学生设置资源配额（`PUT /quotas`，scope=USER，maxCpuCores=4/maxMemoryMb=8192/...）。
2. 学生登录 → 容器配置 → 创建容器：
   - 物理实例下拉选在线实例 → 资源限制字段自动填配额默认值并显示 `[min, max]` 范围。
   - 镜像下拉仅展示已配置镜像（自有/共享/公共），无自由文本输入。
   - 存储池下拉展示各池状态，离线池禁选。
   - 选择镜像后，其 `app_ports` 预填到"容器内端口"（SSH:22 始终含）。
3. 提交 → 后端先下发 `image.load`（受控端 `ImageExists` 命中则跳过，否则下载 tar + `docker load`），成功后再 `container.create`。
4. 验证生成命令含 `--gpus all`（管理端 payload `gpus` 字段 + 受控端 `DeviceRequests{Count:-1}`，日志可见审计字段）。
5. 超配额提交（如 CPU=8 > 4）→ 后端拒绝并提示超出配额。

## 7.2 端到端：心跳回传 IP/GPU/容器状态 + 实时更新

1. 受控端心跳 → 管理端 `physical_instance.ip_address` 取自 `ipAddresses[0]`；`lastStatus` 含结构化 `gpuInfo`。
2. `monitoring_history` 写入 `gpu_memory_total`/`gpu_memory_used`（MiB）。
3. 在受控端直接 `docker stop <容器>` → 下次心跳回传 `state=exited` → 管理端 `container.status` 由 RUNNING 更新为 STOPPED → 前端经 SSE `container` 事件实时刷新。
4. 物理实例状态页展示结构化 GPU（型号/总显存/已用/利用率/温度）与 GPU 显存趋势图。
5. 无 GPU 主机：`gpuInfo` 留空，监控 GPU 显存字段留空，不报错。

## 7.3 端到端：存储池离线 + 创建表单禁选

1. 停止某物理机受控端 → 超过心跳阈值 → 物理实例 OFFLINE → 该机存储池在列表显示"离线"标签。
2. 存储池 `poolPath` 缺失（受控端未回填）→ 即使物理机在线，池显示"离线"。
3. 迁移中的池其物理机同时离线 → 列表同时显示"迁移中"+"离线"两标签。
4. 创建容器表单存储池下拉：离线池被禁用（disabled），无法选中。

## 7.4 端到端：管理员全量课题组管理 + 菜单裁剪

1. 管理员登录 → 课题组信息模块展示全部课题组（`GET /groups`），可新建/编辑/删除/查看成员。
2. 删除课题组 → 成员关系清理，成员用户 `group_id` 置空（用户保留，仍可被管理员管理）。
3. 管理员创建用户无需关联课题组（`group_id` 可空）。
4. 管理员菜单不显示"特需工单"与"公告"（仅显示"工单管理"/"公告管理"）。
5. 学生/导师菜单显示"特需工单"与"公告"。

## 7.5 端到端：镜像拖拽上传 + tar 解析 + 端口预填

1. 镜像管理 → 上传 tar → 拖拽 tar 文件到上传区。
2. 提交后后端解析 `manifest.json` → config JSON `RepoTags`/`ExposedPorts`，回显解析结果（name/tag/app_ports）。
3. 镜像列表"应用端口"列展示解析到的端口。
4. 无 `ExposedPorts` 的 tar → 应用端口为空（用户可手动添加）。
5. 用该镜像创建容器 → 应用端口自动预填"容器内端口"。

## 7.6 回归：既有功能不受影响

1. 既有容器启停重启删、SSH 重置正常。
2. 存储池创建/共享/撤销/迁移流程正常。
3. 镜像 commit/共享/删除、公共镜像同步正常。
4. 心跳保活与离线检测、权限矩阵、通知与工单、公告功能正常。
5. `monitoring_history` 既有指标（CPU/GPU%/内存%）未受 GPU 显存字段新增影响。

## 7.7 文档：镜像 tar 存储位置

已在 [README.md](README.md) "存储与镜像 tar 路径" 与 [design.md](openspec/changes/platform-improvements/design.md) D12 明确。

## 前端展示验证（3.6 / 4.5 / 5.6 / 6.3 前端部分）

- **6.3 IP 列与端口表**：容器列表"IP地址"列取 `instance.ip_address`；连接信息应用端口以 `a-table` 展示"暴露端口"(hostPort)/"容器内端口"(containerPort)（数据源自既有 `ConnectionInfo.apps`）。
- **3.6 拖拽上传**：`a-upload-dragger` 拖拽 + 解析结果回显 + 应用端口列。
- **4.5 实时更新**：SSE `container` 事件触发容器列表状态刷新；物理实例 GPU 信息卡。
- **5.6 管理员视图**：`GroupInfoView` 管理员全量课题组管理；`BasicLayout` 菜单裁剪。
