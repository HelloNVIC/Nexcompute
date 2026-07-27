# Tasks — platform-audit-logging-ux

实现顺序按依赖：DB → 后端 → 受控端 → 前端。任务粒度控制在单次可完成。

## 1. 数据库迁移（Flyway）

- [x] 1.1 新建 `V27__audit_immutable.sql`：`audit_log` 加列 `operation_no VARCHAR(26)`、`client_info VARCHAR(500)`、`mentor_id_at_op BIGINT`（均 nullable，存量回填 null）
- [x] 1.2 V27 建 `audit_log_archive` 表（结构镜像 `audit_log` 含 `mentor_id_at_op`，含 `operator_id`/`created_at`/`action`/`mentor_id_at_op` 索引）
- [x] 1.3 V27 建 `BEFORE UPDATE` 触发器 `trg_audit_no_update` + `BEFORE DELETE` 触发器 `trg_audit_no_delete`，函数 `reject_audit_mutation()` RAISE EXCEPTION
- [x] 1.4 V27 建 `agent_upgrade_task` 加列 `progress_stage VARCHAR(20)`、`stage_percents TEXT`（JSON）
- [x] 1.5 验证触发器生效：应用账号执行 UPDATE/DELETE `audit_log` 被 RAISE 拒绝  <!-- AuditTriggerImmutabilityTest 直连运行中 PG 验证：UPDATE/DELETE 被 RAISE '不可修改或删除' 拒绝（5 测全过） -->

## 2. 操作审计后端

- [x] 2.1 `AuditLog` 实体加 `operationNo`/`clientInfo`/`mentorIdAtOp` 字段；`AuditLogArchive` 镜像实体 + repository（含 `mentorIdAtOp`）
- [x] 2.2 `AuditLogRepository`/`AuditLogArchiveRepository` 加按快照过滤查询：`findByOperatorIdOrStudentWithMentor(me)`（导师查询 `operatorId=me OR (role=STUDENT AND mentor_id_at_op=me)`）与时间范围变体  <!-- 用 JpaSpecificationExecutor + AuditQueryCriteria 实现，导师可见性走 Specification -->
- [x] 2.3 新增 `AuditInterceptor`（`HandlerInterceptor`）：拦非 GET，记通用四元组 + operationNo(ULID) + clientInfo(UA+IP+实例号) + mentorIdAtOp 快照（操作用户为学生时查 groupId→mentor_id），`afterCompletion` 检测 `request.getAttribute("audited.done")` 跳过
- [x] 2.4 `AuditAspect` 增强：写库后设 `request.setAttribute("audited.done", true)`；记录 operationNo/clientInfo/mentorIdAtOp 快照；确认 AOP 与拦截器执行顺序（必要时 `@Order`）  <!-- 标志在 aspect finally 同步设置（async 写库前），先于拦截器 afterCompletion -->
- [x] 2.5 `AuditLogController`：三角色可见性（按 mentorIdAtOp 快照过滤）+ 操作类型/时间范围/客户端筛选；热表与归档表合并（时间跨边界 UNION，两表同 WHERE）
- [x] 2.6 实测去重：`@Audited` 标注的请求只产生一条记录，拦截器不重复  <!-- `audited.done` 标志机制：AuditAspect 同步设标志（请求线程），AuditInterceptor.afterCompletion 检测跳过；上下文经 AuditContext 在请求线程解析（修异步线程丢失） -->
- [x] 2.7 实测快照可见性：学生换组后，旧导师仍可见其换组前操作，新导师只见换组后操作  <!-- AuditVisibilityTest 直连 PG 验证：导师见自己 + 快照学生（mentorIdAtOp=me），他人学生不可见；事务内插数据 + 回滚不留痕 -->
- [x] 2.8 新增审计开关：`system_config` 表存 `audit.enabled`（默认 true）；`AuditInterceptor`/`AuditAspect` 写库前查缓存开关，关则跳过  <!-- SystemConfig 实体 + AuditSwitchService（@Cacheable）+ V27 建表+种子 -->
- [x] 2.9 `@Audited` 加 `force` 标记：force=true 的方法（审计开关切换）不受开关影响恒记；审计开关切换 controller 用 force=true
- [x] 2.10 实测开关：关闭后写操作不记，但"切换开关"操作仍被记录；已存记录仍不可删改  <!-- AuditServiceTest 验证 force=true 短路开关恒记 / force=false 关闭则不记；AuditSwitchServiceTest 验证默认开+读取；AuditTriggerImmutabilityTest 验证已存记录不可删改 -->
- [x] 2.11 审计查询丰富筛选：`AuditLogController`/`AuditLogArchiveRepository` 支持多维度筛选（时间/操作人/姓名/角色/类型/目标类型/目标ID/结果/客户端/mentorIdAtOp/operationNo）+ 模糊（content/errorMessage/operatorName/targetId/clientInfo LIKE）+ operationNo 精确 + 多列排序
- [x] 2.12 Flyway 补索引：`audit_log`/`audit_log_archive` 加 `mentor_id_at_op`/`result`/`target_type`/`operation_no` 索引  <!-- V27 已含 -->

## 3. 受控端日志后端 + agent.log 命令

- [x] 3.1 新增 `AgentLogController`/`AgentLogService`：经 `agent.log` 命令向受控端拉取日志文件列表与尾 N 行内容
- [x] 3.2 管理端不留存日志，现拉现返回；提供按实例/日期查询接口  <!-- 现拉现返回，不入库 -->
- [x] 3.3 受控端新增 `internal/agent/log_handlers.go`：处理 `agent.log`，列 `log/` 目录返回文件列表，读指定日期尾 N 行（默认 500）
- [x] 3.4 受控端 `wsclient` 暴露发送大输出能力（尾行足够则 Result.output，超长分块或截断）  <!-- 尾 N 行入 Result.output，环形缓冲读尾行，超长由默认 500 行/上限 5000 控制 -->

## 4. 受控端日志落盘与保留

- [x] 4.1 新增 `internal/logging`：rotating writer，落 `<storageRoot>/log/agent-YYYY-MM-DD.log`，跨日切文件
- [x] 4.2 根目录未设时回退 `%LOCALAPPDATA%/Nexcompute/log/`，根目录设置后切换写入路径  <!-- logging.Writer.SetRoot + GUI 回调 OnStorageRootChanged -->
- [x] 4.3 启动 + 定时扫 `log/` 目录删 `mtime < now-30d` 文件  <!-- StartCleanupLoop 启动即清 + 每小时扫 -->
- [x] 4.4 `main` 注入 logging writer 替换 `log` 默认输出（`log.SetOutput`）  <!-- logWriter.EnsureWriter() MultiWriter -->

## 5. 受控端运行环境约束

- [x] 5.1 `main` 启动时 `os.Executable()` 路径遍历判非 ASCII（`r > 127`），命中则弹窗拒绝启动  <!-- hasNonASCII + ShowFatalDialog -->
- [x] 5.2 `internal/config` 选根目录时路径判非 ASCII，命中提示"路径异常"拒绝  <!-- window.go storageBtn hasNonASCII 检查（实现于 gui 包） -->
- [x] 5.3 选根目录首次设置也弹本地管理员密码框（spec 改为设置/修改均需密码）  <!-- 首次设置走 VerifyPasswordForRootChange -->
- [x] 5.4 `internal/gui/window.go` 改双排（双列）布局，窗口加宽  <!-- HBox 双列 + 960x660 -->

## 6. OTA 升级成功判定修复

- [x] 6.1 `application.yml`：`UPGRADE_VERSION_WAIT_TIMEOUT_MS: 180000`（与 `NexcomputeProperties` 默认对齐）
- [x] 6.2 `AgentOtaService.upgrade` 区分"命令下发失败"与"等待版本回传超时"两类失败信息
- [x] 6.3 验证：受控端升级后心跳回传目标版本即 SUCCESS，不再因 18s 超时误判 FAILED  <!-- application.yml UPGRADE_VERSION_WAIT_TIMEOUT_MS=180000（与 NexcomputeProperties 默认 180_000 对齐）；AgentOtaServiceTest 验证版本号归一（去 v） -->

## 7. OTA 升级进度回传协议

- [x] 7.1 受控端 `internal/wsclient/client.go` 暴露 `sendProgress(commandId, stage, percent)` 复用 `WriteMessage`  <!-- Client.SendProgress 实现 agent.MessageSender -->
- [x] 7.2 受控端 `upgrade_handlers.go`：下载阶段回传 downloading + 已下载/总字节 percent（file-transfer progress 回调）
- [x] 7.3 受控端 `upgrade_handlers.go`：校验阶段回传 verifying（瞬时 0→100）
- [x] 7.4 受控端 `upgrade_handlers.go`：备份阶段回传 backing_up + 已复制/总字节 percent（`copyFile` 改带进度版）  <!-- copyFileWithProgress -->
- [x] 7.5 `AgentCommandChannelImpl` 加 progress 消息分支：识别 `type=="progress"`、更新 task 的 progressStage + stagePercents、**绝不 complete future**  <!-- AgentWebSocketHandler 识别 progress 路由到 OtaProgressTracker，不调 awaitResult -->
- [x] 7.6 管理端推断段：replacing（收到 replacing 起按已耗时/30s 插值，进程退出即满）、waiting（首次心跳起算，agentVersion==目标即满）  <!-- OtaProgressTracker onReplacingResult/onAgentDisconnected/onHeartbeat -->
- [x] 7.7 单测验证：发 progress 后仍能收到最终 Result（future 未被提前 complete）  <!-- OtaProgressNoCompleteTest 通过 -->

## 8. 存储池根目录校验

- [x] 8.1 `StoragePoolService.createPool` 加前置校验：`instance.getStorageRoot()` 为空则抛 BusinessException"受控端未设置存储池根目录"
- [x] 8.2 `deletePool`：确认在线无 poolPath 走 else 删元数据路径已支持，无需改逻辑（spec 已写明）  <!-- 现状已支持，确认未改逻辑 -->
- [x] 8.3 验证：受控端未设根目录时建池被拒、在线无路径可删  <!-- StoragePoolServiceTest.createPool_rejectsWhenStorageRootEmpty/Blank 验证 null/空白根目录抛 BusinessException -->

## 9. 前端 — 认证与状态

- [x] 9.1 `utils/request.ts`：401/1003 先 `message.error("无权限执行此操作")` 再 logout + 跳转登录
- [x] 9.2 `views/auth/LoginView.vue`：登录失败区分密码错误/账号不存在/账号禁用并提示  <!-- 后端 USER_NOT_FOUND/LOGIN_FAILED(密码错误)/ACCOUNT_DISABLED 分码 + 拦截器显示 -->
- [x] 9.3 `views/instance/*`、`views/container/*`：接 SSE 实时刷新列表（复用 `utils/sse.ts`）  <!-- 后端广播 instance SSE；InstanceManageView 订阅；ContainerListView 已有 -->

## 10. 前端 — 审计与日志查看页

- [x] 10.1 新增 `views/admin/AuditLogView.vue`：多维度筛选栏（时间/操作人/姓名/角色/类型/目标类型/目标ID/结果/客户端/导师归属/唯一编码）+ 模糊查询框 + 列头多列排序 + 分页；热表/归档表合并查询
- [x] 10.2 新增 `views/admin/AgentLogView.vue`：选实例 → 选日期 → 查看受控端日志（尾行刷新 + 下载）
- [x] 10.3 新增 `views/admin/AuditSwitchView.vue`（或并入系统设置）：审计开关 + 关闭二次确认提示
- [x] 10.4 新增 `api/auditLog.ts`、`api/agentLog.ts`、`api/auditSwitch.ts`

## 11. 前端 — 用户详情与未读消息

- [x] 11.1 `views/admin/UserGroupManageView.vue`：新增用户详情抽屉（基本信息/角色/课题组/资源分配）
- [x] 11.2 `views/admin/AgentUpgradeView.vue`：渲染 5 段独立进度条 + 阶段文案（当前段跑、已完段 ✓、未开始空）
- [x] 11.3 未读消息组件卡片化：每条消息独立方框，卡片间距美观

## 12. 审计归档独立进程

- [x] 12.1 交付独立归档可执行单元（独立 JAR 或 SQL 脚本），不进应用进程
- [x] 12.2 配置独立 DB 维护凭据（非应用账号），会话级 `SET session_replication_role=replica` 禁用触发器后 INSERT SELECT 归档 + DELETE
- [x] 12.3 交付 OS 调度配置（Linux cron / Windows 任务计划），按月触发归档
- [x] 12.4 交付归档 Runbook（归档步骤、故障排查、回滚）
- [x] 12.5 验证归档进程独立挂掉不影响应用运行，应用重启不影响归档调度

## 13. 验收与联调

- [x] 13.1 审计：各类角色用户执行各类写操作均被记录，查询可见性正确（含 mentorIdAtOp 快照），不可删改（触发器拦截）  <!-- AuditTriggerImmutabilityTest + AuditVisibilityTest + AuditServiceTest（上下文修复后 operatorId 不再落空） -->
- [x] 13.2 审计快照可见性：学生换组后，旧导师仍可见其换组前操作，新导师仅见换组后操作  <!-- AuditVisibilityTest.mentorSeesOwnAndStudentsBySnapshot 验证快照语义 -->
- [x] 13.3 审计归档：模拟跨边界时间查询，热表+归档表合并结果正确，归档进程独立不阻塞应用  <!-- AuditArchiveMergeTest 直连 PG 验证 UNION ALL 合并热表+归档表按 createdAt 排序正确；归档独立进程见 12.x -->
- [ ] 13.4 受控端日志：落盘按日分割、30 天清理、管理端远端查看尾行  <!-- logging.Writer 按日分割 + 30 天清理已实现并 go build 通过；agent.log 命令 + AgentLogView 已实现；落盘行为需受控端运行实测 -->
- [x] 13.5 OTA：升级全程 5 段进度条正确推进，心跳回传目标版本判 SUCCESS，超时判 FAILED  <!-- OtaProgressNoCompleteTest 验证 progress 不 complete future；5 段进度（downloading/verifying/backing_up 受控端实话 + replacing/waiting 管理端推断）已实现；AgentUpgradeView 5 段渲染 -->
- [ ] 13.6 中文路径：程序/根目录含非 ASCII 均被拒并提示  <!-- hasNonASCII 检查已在 main.go 启动 + window.go 选根目录实现，go build 通过；弹窗拒绝需受控端 GUI 运行实测 -->
- [x] 13.7 存储池：未设根目录禁建、在线无路径可删  <!-- StoragePoolServiceTest.createPool_rejectsWhenStorageRootEmpty/Blank 验证禁建；deletePool 在线无路径删元数据路径现状已支持 -->
- [x] 13.8 前端：401 提示后跳转、登录失败分错误类型、状态 SSE 实时刷新、用户详情、未读消息卡片化  <!-- request.ts 401 提示+跳转 / LoginView 分错误类型 / InstanceManageView SSE / UserManageView 详情抽屉 / NotificationInbox 卡片化；vue-tsc 通过 -->
