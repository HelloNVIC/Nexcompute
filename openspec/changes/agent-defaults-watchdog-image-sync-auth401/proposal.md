# Proposal

## Why

四个独立但同属"平台可用性加固"的问题：(1) 受控端心跳每秒将全局管理员密码经 `config.Update` 整体重写本地配置文件，手动编辑的配置 1 秒内被内存旧值覆盖，且存储池根目录无默认值导致新装机器必须手工设置；(2) Docker Desktop 看门狗现每 10 分钟才检查一次，daemon 意外退出后最长 10 分钟无人拉起，实验室机器在此期间不可用；(3) 镜像只能逐机"创建容器时按需拉取"，管理员无法把一个镜像一次性铺到全部机器并看到各机实时进度；(4) 登录失效（JWT 过期）时后端经 Spring Security 默认入口返回 403+空 body，前端只弹"无权限执行此操作"不登出不跳转，轮询页面无限弹 toast。

## What Changes

- **受控端配置默认值**：`defaultCfg` 增加存储池根目录默认值 `D:\lab404`；加载配置时若 `storageRoot` 为空（含配置文件已存在的存量机器）自动补填该默认值并持久化；补填后 `storageRootLocked` 保持 false，用户仍可经 GUI（密码校验）修改。
- **心跳重写修复**：受控端收到心跳回包的全局管理员密码与本地一致时不再触发 `config.Update`，配置文件仅在内容实际变化时写盘。
- **Docker Desktop 看门狗提速**：检查间隔 10 分钟 → 30 秒，取消 2 分钟首查延迟（启动即查）；新增拉起冷却（拉起后 60 秒内不重复拉起，防 daemon 启动期进程风暴）与拉起失败日志限流（防 Docker 未安装机器每 30 秒刷错误日志）。
- **镜像同步到所有机器**：镜像管理列表对仓库类且有效的镜像新增"同步到所有机器"操作（仅管理员）：为全部在线实例下发 `image.pull`，离线实例直接标记"离线跳过"；同步批次与每实例任务落库（V36 新表），每命令占一个线程 `dispatchAndWait`；各实例拉取进度（百分比+层文本）经既有 SSE 通道实时推送，前端以抽屉展示机器 × 状态进度表。
- **登录失效 401 修复**：后端 `SecurityConfig` 配置 `authenticationEntryPoint`，未认证（无/过期 token）请求统一返回 HTTP 401 + 业务码 1003；前端 401/1003 路径提示文案统一为「登录已失效，请重新登录」（替换易与权限拒绝混淆的「无权限执行此操作」），登出并跳转登录页，并发失效请求只提示一次；真实权限拒绝（403 + PERMISSION_DENIED，消息含模块:动作后缀）仅提示不登出。

## Capabilities

### New Capabilities

（无——四项均落在既有能力边界内）

### Modified Capabilities

- `controlled-agent`：
  - 修改「存储池根目录设置与保护」：增加默认值 `D:\lab404` 与空值自动补填语义（新装与存量一致生效，补填不锁定）；
  - 新增「本地配置持久化稳定性」：配置文件仅在内容变化时重写，周期性心跳回包未变化不得触发写盘；
  - 新增「Docker Desktop 常驻看门狗」：每 30 秒检查 daemon 可达性，不可达自动拉起，含拉起冷却与失败限流。
- `registry-image-management`：
  - 新增「镜像同步到所有机器」：管理员一键向全部在线实例下发 `image.pull`，离线标跳过，任务落库，SSE 实时展示每实例进度与终态（拉取中/已存在/完成/失败/超时/离线跳过）。
- `access-control`：
  - 修改「登录会话失效与失败提示」：未认证请求统一 401+业务码 1003（后端），前端失效提示文案改为「登录已失效，请重新登录」、并发只提示一次；403（真实权限拒绝）提示真实原因不登出。

## Impact

- **controlled-agent（Go）**：`internal/config/config.go`（默认值+补填）、`internal/heartbeat/heartbeat.go`（密码判等）、`internal/docker/desktop.go`（看门狗间隔/冷却/限流）。
- **management-backend（Java）**：`security/SecurityConfig.java`（entryPoint）；新增镜像同步服务与端点（`ImageController`/`ImageService` 扩展）；Flyway **V36** 新增同步任务表（batch + 每实例任务，含状态/进度/错误信息）；复用 `AgentCommandService`、`ProgressRouter`、`SseService`。
- **management-frontend（Vue）**：`utils/request.ts`（401 文案与 toast 去重）；`views/image/ImageListView.vue` + `api/image.ts`（同步按钮、进度抽屉、SSE 订阅）。
- **语义影响**：存储池根目录默认补填后，管理端"存储池根目录未设置警告、禁建池"保护（storage-pool 既有 requirement）实际不再触发——默认目录不存在时由受控端创建目录兜底（`MkdirAll`），属预期行为变化。
- **数据库**：新增 V36 迁移（不可回滚地引入同步任务表）。
