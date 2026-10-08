# Design

## Context

见 proposal.md（Why）。相关现状：受控端心跳间隔 1 秒、`HeartbeatController` 非首次心跳每次回传全局密码、`config.Update` 全量重写 exe 旁的 `nexcompute-agent.json`；`desktop.go` 看门狗为 2 分钟首查延迟 + 10 分钟周期；`image.pull` 命令、`ProgressRouter`（commandId→listener）、`SseService.pushToUser`、`AgentCommandService.sendCommand(…, listener)` 五参重载均已就绪（registry-image-distribution D5/D6）；`SecurityConfig` 未配置 `exceptionHandling`，未认证请求落入 Spring Security 默认 `Http403ForbiddenEntryPoint`（403+空 body）。

## Goals / Non-Goals

**Goals:**
- 存储池根目录默认 `D:\lab404`，新装与存量空值统一生效且不锁定
- 配置文件不再被心跳周期性重写（仅内容变化时写盘）
- Docker daemon 意外退出后 ≤30 秒自动恢复，且无进程风暴/日志洪水
- 管理员可一键向全部在线机器分发镜像并实时看每机进度，历史可恢复
- 登录失效统一 401+1003，前端登出跳转一次完成

**Non-Goals:**
- 离线机器上线补拉镜像（仅标"离线跳过"）
- 同步批次"取消"操作
- 看门狗 GUI 开关（用户主动退出 Docker Desktop 仍会被拉回，属既有托管语义的提速）
- 受控端免升级：镜像同步复用旧版已实现的 `image.pull`，本 change 三端均可独立部署

## Decisions

### 1. 配置默认值与补填（受控端）

- `defaultCfg` 增加 `StorageRoot: ` `D:\lab404` ``（raw string，JSON 序列化为 `D:\\lab404`）。
- `loadOrDefault` 在 `json.Unmarshal` 之后判断：`StorageRoot == ""` → 填默认值并 `persist`（存量补填）；新装路径（文件不存在）经 defaultCfg 自然获得默认值后照常 persist。两路径统一在返回前执行一次 `MkdirAll`（失败仅记日志不阻断启动，防 D 盘缺失机器启动失败）。
- 补填不设置 `StorageRootLocked`（保持 false）：GUI 仍走"首次设置"路径（密码 + `SetRoot`），与 spec「补填不视为锁定」一致。
- 备选（拒绝）：后端经心跳下发默认值——引入管理端与受控端耦合，无必要。

### 2. 心跳重写修复（受控端）

- `heartbeat.go` 补推分支加判等：仅当 `result.Data.LocalAdminPassword != ""` **且** 与当前 `cfg.LocalAdminPassword` 不同时才 `config.Update`。
- 备选（拒绝）：后端按需下发（密码版本号/变更时间戳），需要状态协议扩展，受控端判等一行即达到同等效果。
- 加固项（一并做）：`persist` 改原子写（写 `.tmp` 后 `os.Rename`，防写一半进程被杀损坏 JSON）；`heartbeat.go` 中被 `_ =` 吞掉的 `config.Update` 错误至少 `log.Printf`。

### 3. 看门狗 30 秒 + 冷却 + 限流（受控端）

- `desktop.go`：`ticker` 10min → 30s；删除 2 分钟首查 `time.Sleep`（启动即查）。
- 冷却：goroutine 内记录 `lastStartAttempt`，daemon 不可达但距上次拉起 <60s 时跳过拉起（下周期复查）。
- 失败限流：`failedStreak` 计数，仅状态转换（成功→失败、失败→成功）与每第 N 次（如 20 次 ≈10 分钟）记录日志。
- 检查方式维持 daemon `Ping`（引擎就绪才是目标），备选进程名探测（tasklist）不能反映引擎状态，拒绝。
- **拉起路径候选补全（实测修正）**：DESKTOP-9IQCUKC 实测看门狗 30 秒检测与限流均正常，但拉起报"未在常见路径找到 Docker Desktop.exe"——实验室机器存在 per-user 安装。候选补齐两种形态：`%LOCALAPPDATA%\Docker\Docker\` 与 `%LOCALAPPDATA%\Programs\DockerDesktop\`（如 `C:\Users\Admin\AppData\Local\Programs\DockerDesktop`），机器级 `Program Files` 候选保留最优先。

### 4. 镜像同步数据模型（V36）

- 新表 `image_sync_batch`：`id` PK、`image_id`（FK 镜像表）、`image_ref`（拉取引用快照，如 `10.13.66.25:5000/lab404-jupyter:0.1`）、`initiated_by`（发起管理员 user id）、`status`（RUNNING/DONE）、`created_at`、`finished_at`。
- 新表 `image_sync_task`：`id` PK、`batch_id` FK、`instance_id`/`instance_number`（快照）、`command_id`（UUID，对应 WS 命令）、`status`（PENDING/PULLING/PULLED/ALREADY_EXISTS/FAILED/TIMEOUT/OFFLINE_SKIPPED）、`percent`、`last_text`、`error_message`、时间戳列。
- 迁移遵循 V32/V33 风格（`COMMENT ON` 中文列注释），SQL 全文不得出现 `${`；`ddl-auto=validate` 要求实体字段与 DDL 完全一致。
- 汇总（成功/失败/跳过计数）由任务聚合计算，批次表不冗余存储。

### 5. 同步执行流（后端 ImageSyncService）

```
POST /images/{id}/sync-all（管理员）
  ├─ 校验：distribution=REGISTRY && registryValid；该镜像无 RUNNING 批次（否则 409 拒绝）
  ├─ 实例全集 = PhysicalInstance 全表；按 AgentCommandService.isAgentConnected 二分
  ├─ 建 batch + tasks（离线任务即刻 OFFLINE_SKIPPED 终态并直接推 SSE）
  ├─ 在线任务逐个提交固定线程池（16，超出排队；实验室规模不会触顶）
  │    每任务：sendCommand(inst, "image.pull", {imageRef}, 600_000, listener)
  │      listener（ProgressRouter 回调）：节流更新 task 行（≥1s 或状态变化）+ pushToUser(adminId, "imageSyncProgress", event)
  │    dispatchAndWait 返回后落终态："pulled"→PULLED，"already_exists"→ALREADY_EXISTS，
  │      null(超时)→TIMEOUT，error→FAILED+error_message；终态也推一帧 SSE
  └─ 全部任务终态后 batch.status=DONE
```

- 复用五参 `sendCommand` 重载（listener 生命周期=命令生命周期，由该方法注册/注销）；`imageRef` 拼装复用 ContainerService/push-commands 既有 registryUrl 来源，不新引配置。
- "每命令占一个线程"为已定决策；备选回调驱动（改 AgentCommandChannel 核心去 latch 等待）侵入大，实验室规模收益为零。

### 6. 同步 REST 与权限（后端）

- `POST /images/{id}/sync-all` → 返回 batch + 任务列表；`GET /images/sync-batches/{batchId}` → 恢复视图；`GET /images/{id}/sync-batches/active` → 进行中批次或空（前端按钮点击时探测）。
- 权限：`@RequirePermission(module = "images", action = EDIT)` + 服务内校验管理员角色，对齐"无标记镜像"入口的管理员限定方式（前端 `isAdmin` 隐藏入口仅为体验层）。
- SSE 事件 `imageSyncProgress`：`{batchId, instanceNumber, status, percent, text, error}`，复用既有 `sse.ts` 订阅工具（containerImagePull 同款）。建批初始态、每次状态变化、终态均推帧，保证前端表格无需轮询即可完整渲染（覆盖 `already_exists` 秒回无进度事件的场景）。

### 7. 前端同步视图（Vue）

- `ImageListView` 操作列：REGISTRY 且 `registryValid` 且 admin 显示「同步到所有机器」；点击先 `Modal.confirm`（说明将下发全部在线机器），随后 POST 或经 active 接口直接恢复进行中批次。
- `a-drawer` 展示：批次头（镜像引用 + 成功/失败/跳过计数）+ `a-table`（实例编号 / 状态 Tag / 进度条 + 层文本 tooltip / 错误信息）。SSE 事件按 `instanceNumber` 更新对应行；抽屉关闭不取消 SSE（复用全局订阅）。

### 8. 401 入口点（后端 + 前端）

- 后端：`SecurityConfig.filterChain` 增加 `.exceptionHandling(e -> e.authenticationEntryPoint(...))`，手写 JSON 响应：HTTP 401 + `ApiResponse.error(TOKEN_INVALID)`（注入 ObjectMapper 序列化，保持与 GlobalExceptionHandler 相同响应形状）。permitAll 路径（`/agent/**`、`/auth/**` 等）不受影响；已认证无权限仍走 GlobalExceptionHandler 的 403+body，不动 `accessDeniedHandler`。
- 备选（拒绝）：在 JWT 过滤器内解析失败即写 401——提前拒绝会改变过滤器"尽力认证、放行决定权交给授权层"的结构，permitAll 语义易被误伤。
- 前端 `request.ts`：模块级 `redirecting` 标志——401/业务码 1003 首次触发时弹一次「登录已失效，请重新登录」+ logout + 跳转并置标志，并发后续请求静默 reject；HTTP 200+code 1003/401 分支的误导文案「无权限执行此操作」同步改为「登录已失效，请重新登录」；裸 403（空 body）分支保留防御性提示不登出（修复后不应再出现）。

## Risks / Trade-offs

- [V36 上线后不可回滚] → 迁移仅建新表无数据搬运；极端回滚=停用同步功能，无存量数据风险。
- [存量补填令"存储池未设置"保护失活] → 预期行为变化（proposal Impact 已声明）；默认目录由补填时 `MkdirAll` 兜底创建。
- [D 盘缺失机器：MkdirAll 失败后 logging 写 `D:\lab404\log` 异常] → 实现时验证 `logging.Writer.EnsureWriter` 对不可写根目录的容错，必要时沿用 LOCALAPPDATA 回退逻辑（tasks 含验证项）。
- [30s 看门狗与用户主动退出 Docker Desktop 对抗] → 既有托管语义（原 10min 看门狗同样会拉起），仅提速；GUI 开关列为 Non-Goal。
- [同步占用线程池 16×10min] → 实验室规模（机器数<16）无排队；超限排队仅延迟不丢失。
- [手动编辑配置仍需重启受控端生效] → 语义保持不变（配置启动时加载），本修复只消除"运行中被周期覆盖"。

## Migration Plan

三端可独立部署，无顺序依赖：后端（entryPoint + 同步 API + V36）与前端同期发布即获得议题 3/4；受控端改动经既有 OTA 通道下发。旧版受控端已支持 `image.pull`，无需为同步功能先行升级。

## Open Questions

- SSE 事件名与进度写库节流间隔（暂定 1s）等纯实现细节，实现期定即可。
- 同步批次未来是否提供"取消"与离线补拉，待真实使用反馈另立 change。
