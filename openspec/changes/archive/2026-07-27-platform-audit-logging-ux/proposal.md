## Why

平台经四轮迭代已具备完整业务闭环，但投用与合规层面集中暴露五类缺口：

1. **操作无审计账本**：现有 `@Audited`/`AuditAspect` 只覆盖 14 个 service 的部分写方法（51 处注解），靠人工铺注解，新加方法易漏；审计表仅管理员可见，缺三角色分层可见性；`audit_log` 在 DB 层完全可改可删（JPA `@CreationTimestamp` 只防 JPA 改 `created_at`，直接 SQL 不拦）；字段缺"客户端"与"操作记录唯一编码"。合规上不达标。
2. **受控端无日志可追溯**：受控端现用 Go 标准 `log` 包输出到 stdout/stderr，无落盘、无按日分割、无保留期，现场排障只能靠复现。需求要求落存储池 `log` 文件夹、按日分割、保留 30 天。
3. **OTA 升级误判失败 + 无进度可见性**：升级"已成功回传新版本仍提示失败"——根因是 `application.yml` 的 `UPGRADE_VERSION_WAIT_TIMEOUT_MS:18000`（18s）远小于受控端替换→重启→首次心跳的实际耗时（常 30–60s），超时即判 FAILED，但新版随后正常心跳，故用户看到"已回传却失败"。且升级全程只有 `PENDING/SUCCESS/FAILED` 三态，无"下发文件中/安装中/等待心跳中"阶段可见性。
4. **前端体验断层**：登录失效（401）静默跳转无提示（403 才提示"无权限执行此操作"）；登录失败无具体错误；物理实例/容器状态靠手动刷新非实时；管理员"用户与课题组管理"缺用户详情；未读消息列表朴素无卡片化。
5. **受控端运行环境与存储池约束缺失**：中文路径下 cmd `.bat`/PowerShell/Docker 行为诡异（OTA `updater.bat` 注释已点明"cmd.exe 按 ANSI 解析 .bat，中文字节错位"），但启动与选存储池根目录均无中文路径检查；选根目录首次设置不索密码（spec 现仅"修改"需密码）；GUI 单排偏窄。存储池建池不校验受控端是否已设根目录，可建出无路径的脏池。

本变更批量补齐，使平台具备**合规级操作审计、可追溯的受控端日志、可信的 OTA 升级判定与进度、连贯的前端体验、健壮的运行环境约束**。

## What Changes

**操作审计（新独立 capability `audit`）**
- 新建 `audit` capability，从 `access-control` 抽出审计功能为独立模块。
- **覆盖策略（丙·混合）**：新增全局 `HandlerInterceptor` 兜底记录所有**非 GET** 写操作（零遗漏），保留 service 层 `@Audited` 补业务语义（action/targetType/targetId）。`@Audited` 执行时设 request 标志，拦截器 `afterCompletion` 检测到标志则跳过——**一请求一记录，`@Audited` 优先**（去重方案 c·拦截器让路）。
- **三角色可见性**：管理员=全表；导师=自己 + 操作时归属自己的学生（按**操作时导师归属快照** `mentorIdAtOp` 判定，不因学生后续换组丧失历史可见性）；学生=自己。
- **字段补全**：`audit_log` 新增 `operation_no`（唯一编码，ULID 有序短码）、`client_info`（UA + IP，受控端操作时含实例编号）、`mentor_id_at_op`（操作时导师归属快照）列。
- **不可删改**：PG `BEFORE UPDATE`/`BEFORE DELETE` 触发器 `RAISE EXCEPTION`，锁运行期应用账号（含 admin），DBA 归档走禁用触发器运维路径。
- **保留与归档**：近 30 天热数据留 `audit_log`；每月归档 30 天前记录至 `audit_log_archive`（**独立进程 + 独立 DB 维护凭据 + OS 级调度**，不进应用进程、不阻塞业务）。前端审计页**同时查询热表与归档表**：默认查热表，时间范围跨归档边界时合并两表结果（按 createdAt 统一排序），用户无感知归档分界。
- **丰富查询/排序/筛选**：审计查询界面支持多维度筛选（时间/操作人/姓名/角色/类型/目标类型/目标 ID/结果/客户端/导师归属/唯一编码）、模糊匹配（content/errorMessage/operatorName/targetId/clientInfo LIKE）与 operationNo 精确、多列排序（列头切换升降，默认 createdAt 倒序）。跨边界合并查询同样支持。
- **审计开关**：管理员可配置是否开启审计（存系统配置，默认开）。关闭后拦截器与 `@Audited` 跳过写库（完全停记）。**护栏**：开关自身的切换操作恒被审计（即使审计关闭也记"管理员开启/关闭审计"），防掩盖痕迹；关闭前端二次确认。开关与不可删改正交——只控是否记新操作，已存记录仍触发器保护。

**受控端日志 + 远端集中查看**
- 受控端新增 lumberjack-style rotating writer：日志落 `<存储池根目录>/log/agent-YYYY-MM-DD.log`，按日分割。
- 保留 30 天：启动 + 定时扫 `log/` 目录，删 `mtime < now-30d` 文件。
- 存储池根目录未设时，回退落 `%LOCALAPPDATA%/Nexcompute/log/`，待根目录设置后迁移。
- （PG 触发器不适用此层，归审计不可变。）
- **远端上报与集中查看**：受控端响应管理端 `agent.log` 命令，按日期范围返回日志文件列表与内容（复用既有 file-transfer 下载日志文件，或按行流式返回尾 N 行）。管理端新增"受控端日志查看"页：选物理实例 → 选日期 → 查看当日日志（支持下载、尾部实时刷新）。日志元数据不入库，按需现拉。

**OTA 升级（改 `agent-ota` capability）**
- **成功判定根因修复**：`application.yml` 的 `UPGRADE_VERSION_WAIT_TIMEOUT_MS` 由 `18000` 提到 `180000`（与 `NexcomputeProperties` 默认对齐）。心跳回传目标版本即 SUCCESS，超时 ≥180s 未确认才 FAILED。
- **进度阶段回传（B 轻量版）+ 每段独立百分比**：受控端 `handleUpgrade` 在 `Execute` 内回传 `{type:"progress", commandId, stage, percent}` 消息；最后回 `Result{status:"replacing"}` 收尾。`AgentCommandChannelImpl` 加 progress 分支：**识别、更新任务阶段与该段百分比、绝不 complete future**。前端渲染 **5 段独立进度条**（每段 0–100%），当前段在跑、已完段锁定 100% 带 ✓、未开始段空：

  | 段 | 名称 | 0→100% 来源 |
  |----|------|------------|
  | ① | 下发文件中 | 受控端实话：已下载字节 / 总字节（file-transfer 知总大小，progress 回调回传） |
  | ② | 校验中 | 瞬时 0→100%（MD5 秒级，不做字节进度回调） |
  | ③ | 备份中 | 受控端实话：已复制字节 / 总字节（`copyFile` 改带进度版） |
  | ④ | 替换重启中 | 管理端推断：收到 replacing 起、按已耗时 / 预期 30s 线性插值；进程退出即该段 100% |
  | ⑤ | 等待版本确认中 | 管理端推断：首次心跳起算，`agentVersion == 目标` 即 100% + SUCCESS；多数瞬时跳满，异常态（重启了但版本没对上）才爬升——正好暴露该异常 |

- `AgentUpgradeTask` 加 `progressStage`（当前段）+ `stagePercents`（JSON，各段百分比）列；前端 `AgentUpgradeView` 渲染 5 段独立进度条与阶段文案。

**前端 UX**
- `request.ts` 拦截器：401（登录失效）先 `message.error("无权限执行此操作")` 再 logout + 跳转登录。
- `LoginView`：登录失败区分密码错误 / 账号不存在 / 账号禁用并提示。
- 物理实例/容器列表接 SSE 实时刷新（确认现有 `utils/sse.ts` 复用）。
- 管理员"用户与课题组管理"新增用户详情抽屉。
- 未读消息每条卡片化美化。

**受控端运行环境**
- 启动时 `os.Executable()` 路径判非 ASCII → 弹窗拒绝启动。
- 选存储池根目录时路径判非 ASCII → 拒绝。
- 选根目录**首次设置也需本地管理员密码**（spec 由"仅修改需密码"改为"设置/修改均需密码"）。
- GUI 改双排布局、加宽窗口。

**存储池**
- `StoragePoolService.createPool` 加前置校验：`instance.getStorageRoot()` 为空则拒绝建池（"受控端未设置存储池根目录"）。根目录状态经心跳已落库 `PhysicalInstance.storageRoot`，零协议改动。
- `deletePool`：在线但 `pool.poolPath` 为空时仍删元数据（现状已支持，spec 写明语义）。

## Capabilities

### New Capabilities
- `audit`：操作审计独立模块——全局拦截器兜底非 GET 写操作 + `@Audited` 业务语义（丙·混合，拦截器让路去重）；三角色可见性；`operation_no`/`client_info` 字段；PG 触发器不可删改；30 天热数据 + 每月归档。

### Modified Capabilities
- `controlled-agent`：日志落存储池 `log/` 按日分割保留 30 天（根目录未设回退 LOCALAPPDATA）；启动/选根目录非 ASCII 路径检查；选根目录首次设置也需密码；GUI 双排布局加宽；新增 `agent.log` 命令支持管理端按日期拉取受控端日志文件列表与内容。
- `agent-ota`：升级成功判定改心跳回传目标版本（修 18s 超时根因）；受控端分阶段回传 progress（含 stage + percent）+ channel 加 progress 分支（不 complete future）；前端 5 段独立进度条（每段 0–100%，①②③ 受控端实话 / ④⑤ 管理端推断）；`AgentUpgradeTask` 加 `progressStage` + `stagePercents`。
- `storage-pool`：建池前置校验受控端 storageRoot 非空；在线无 poolPath 可删元数据语义写明。
- `notifications`：未读消息卡片化（前端）。

## Impact

**管理端后端（SpringBoot）**
- 新增 `AuditInterceptor`（`HandlerInterceptor`，拦非 GET，记通用四元组 + operationNo/clientInfo，request 标志让路 `@Audited`）。
- `AuditAspect` 增强：写库后设 `request.setAttribute("audited.done", true)`；补 `operationNo`(ULID)/`clientInfo`。
- `AuditLog` 实体加 `operationNo`/`clientInfo`/`mentorIdAtOp` 列；`AuditLogRepository`/`AuditLogArchiveRepository` 加按快照过滤查询（导师：`operatorId=me OR (role=STUDENT AND mentor_id_at_op=me)`）。
- `AuditLogController`：三角色可见性（按 `mentorIdAtOp` 操作时快照过滤），加操作类型/时间范围/客户端筛选；热表+归档表合并查询；前端审计页配套。
- `AuditAspect`/`AuditInterceptor`：写库前查操作用户 `groupId`→`mentor_id` 填 `mentorIdAtOp` 快照。
- Flyway `V27__audit_immutable.sql`：`audit_log` 加列 + `BEFORE UPDATE`/`BEFORE DELETE` 触发器；`audit_log_archive` 建表。
- `AgentOtaService`：超时配置对齐 180s；`AgentUpgradeTask` 加 `progressStage` + `stagePercents`（各段百分比 JSON）；`AgentCommandChannelImpl` 加 progress 消息分支（识别不 complete future）+ 推断段（④替换重启中/⑤等待版本确认中）百分比插值。
- `StoragePoolService.createPool`：加 storageRoot 判空前置校验。
- `application.yml`：`UPGRADE_VERSION_WAIT_TIMEOUT_MS: 180000`。
- 新增 `AgentLogController`/`AgentLogService`：经 `agent.log` 命令向受控端拉取日志文件列表与内容（复用 file-transfer 下载或流式返回尾 N 行）；管理端不留存日志，现拉现返回。
- `AuditLogController`：查询支持热表 + 归档表合并（时间范围跨边界时 UNION 后排序）。

**受控端（Go / Fyne）**
- 新增 `internal/logging`（rotating writer：`<root>/log/agent-YYYY-MM-DD.log`，按日切分，30 天清理，根目录未设回退 LOCALAPPDATA）。
- `main`：启动时 exe 路径非 ASCII 检查；注入 logging writer 替换 `log` 默认输出。
- `internal/config`：选根目录时非 ASCII 检查 + 首次设置也索密码。
- `internal/gui/window.go`：双排布局加宽；选根目录按钮首次设置也弹密码框。
- `internal/agent/upgrade_handlers.go`：分阶段回传 progress 消息（downloading/verifying/backing_up），最后回 replacing Result。
- `internal/wsclient/client.go`：暴露 `sendProgress(commandId, stage)` 复用 `WriteMessage`。
- 新增 `internal/agent/log_handlers.go`：处理 `agent.log` 命令，按日期范围列出 `log/` 文件、返回内容（尾 N 行或经 file-transfer 下载）。

**管理端前端（Vue 3 / AntD）**
- `utils/request.ts`：401 加"无权限执行此操作"提示后跳转。
- `views/auth/LoginView.vue`：登录失败区分错误类型。
- `views/instance/*`、`views/container/*`：接 SSE 实时刷新列表。
- 新增 `views/admin/AuditLogView.vue`（三角色可见性 + 筛选 + operationNo；热表/归档表合并查询，时间跨边界自动 UNION）。
- 新增 `views/admin/AgentLogView.vue`（选实例 → 选日期 → 查看受控端日志，支持下载 + 尾部刷新）。
- `views/admin/UserGroupManageView.vue`：新增用户详情抽屉。
- `views/admin/AgentUpgradeView.vue`：渲染 5 段独立进度条 + 阶段文案（当前段在跑、已完段带 ✓、未开始段空）。
- `components/` 未读消息卡片化（`NotificationInbox` 或 BasicLayout 内组件）。

## Non-goals
- 中文路径以外的国际化路径处理（仅挡非 ASCII 并提示"路径异常"，不做编码转换/兼容）。
- 受控端日志的结构化解析与检索引擎（按日期+尾行查看 + 下载即够，不做全文检索/日志聚合如 ELK）。
- 审计归档的自动调度编排（归档脚本/定时任务存在，但调度接入运维侧 cron 而非应用内调度器，本变更只产出脚本与 runbook）。
- 中文路径以外的国际化路径处理（仅挡非 ASCII，不做编码转换兼容）。
- 升级进度"百分比"精确度（阶段是离散的，不做连续百分比估算）。
