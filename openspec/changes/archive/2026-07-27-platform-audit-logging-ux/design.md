## Context

平台经四轮迭代（`build-nexcompute-platform` → `platform-improvements` → `platform-refinements` → `platform-env-ota-realtime`）已闭环可用。本变更是第五轮，针对投用与合规层暴露的五类缺口：操作无审计账本、受控端无日志可追溯、OTA 升级误判失败且无进度可见、前端体验断层、运行环境约束缺失。

现状关键约束（已核实于代码）：

1. **审计半成品**：`@Audited`/`AuditAspect`/`AuditLog`/`AuditLogController` 已存在，注解打在 **service 层**（14 个 service、51 处），靠人工铺，覆盖率不可保证。`audit_log` 表 V1 建表无任何不可变约束，JPA `@CreationTimestamp updatable=false` 仅防 JPA 改 `created_at`，直接 SQL 的 UPDATE/DELETE 放行。审计仅管理员可见。
2. **受控端日志全 stdout**：用 Go 标准 `log` 包，无落盘、无分割、无保留。`storageRoot` 已经心跳上报并落库 `PhysicalInstance.storageRoot`（`heartbeat.go:123` → `HeartbeatService:86`）。
3. **OTA 误判根因**：`AgentOtaService.waitForVersionUpgrade` 轮询 `instance.agentVersion == targetVersion`，超时判 FAILED。`NexcomputeProperties.upgradeVersionWaitTimeoutMs` 默认 `180_000`（180s），但 `application.yml:66` 覆盖为 `UPGRADE_VERSION_WAIT_TIMEOUT_MS:18000`（18s）。受控端替换→退出→`updater.bat` 重命名/移动/启动→新 exe 初始化→首次心跳常 30–60s，18s 超时即 FAILED，但新版随后正常心跳，DB 里 `agentVersion` 是对的——用户看到"已回传新版本还是提示失败"。
4. **WS 协议一问一答**：受控端 `Executor.Execute` 同步返回单个 `Result`，`wsclient` 回一次包；`AgentCommandChannelImpl.dispatchAndWait` 单 future、回包即移除。受控端 `Execute` 返回后立即 `quitFunc()` 退出，进程消失，**不可能再从原进程回传任何消息**。
5. **审计/日志/OTA 横跨三端**：受控端（Go/Fyne）、管理端后端（SpringBoot）、管理端前端（Vue 3/AntD）。

## Goals / Non-Goals

**Goals:**
- 操作审计 100% 覆盖所有非 GET 写操作（零遗漏），三角色分层可见性，DB 级不可删改，30 天热数据 + 每月归档，热表/归档表合并查询。
- 受控端日志落存储池 `log/` 按日分割保留 30 天，且支持管理端远端按日期拉取查看。
- OTA 升级成功判定改心跳回传目标版本（根因修复），5 段独立进度条（每段 0–100%）。
- 前端体验连贯：401 提示后跳转、登录失败分错误类型、状态 SSE 实时刷新、用户详情、未读消息卡片化。
- 受控端运行环境健壮：非 ASCII 路径检查（程序 + 存储池）、选根目录首次也索密码、GUI 双排加宽。
- 存储池建池前置校验根目录已设。

**Non-Goals:**
- 受控端日志全文检索/聚合引擎（按日期+尾行查看+下载即够）。
- 审计归档自动调度编排（脚本+runbook 存在，调度接运维侧 cron）。
- 中文路径以外的国际化路径处理（仅挡非 ASCII + 提示路径异常，不做编码转换）。

## Decisions

### D1. OTA 成功判定：心跳回传目标版本（根因修复）

**选**：`UPGRADE_VERSION_WAIT_TIMEOUT_MS` 从 `18000` 提到 `180000`（与 `NexcomputeProperties` 默认对齐）。`waitForVersionUpgrade` 逻辑不变——心跳回传目标版本即 SUCCESS，超时 ≥180s 未确认才 FAILED。

**为什么不是别的**：
- *单纯加长超时不够*？根因是 18s 远小于实际耗时，加到 180s 已消除假阴性。逻辑本身（轮询 `agentVersion`）是正确的判定方式——心跳是新版本活着的唯一可信信号。无需改判定逻辑，只需修配置。
- *为何不引入"新版自报 cmdId 续接"*？那要新版 exe 启动后发一条"我是 OTA 重启的，原任务 X"消息，管理端据此提前判 SUCCESS。比心跳轮询早几秒，但要改新版 exe 启动协议 + 管理端识别逻辑，收益（早几秒）不抵风险。心跳已足够早（首次心跳通常在新版启动后 1–3s）。

**超时值**：180s 上限留足余量。最坏链路（大 exe 下载 + 慢磁盘 + Docker 自检 + 心跳间隔）实测 <90s，180s 是 2 倍冗余。

### D2. OTA 进度：5 段独立进度条 + progress 消息协议（B 轻量版）

**选**：受控端 `handleUpgrade` 在 `Execute` 内回传 `{type:"progress", commandId, stage, percent}` 消息，`AgentCommandChannelImpl` 加 progress 分支——**识别、更新任务阶段与该段百分比、绝不 complete future**，最后 `Result{status:"replacing"}` 收尾。前端渲染 **5 段独立进度条**（每段 0–100%），而非全程一条混合条。

**为什么 5 段独立而非全程一条**：全程混合条要给每段一个权重（下载 40% + 校验 5% + ...），下载段实际耗时随 exe 大小变，权重失真；且前段"阶梯跳变"不直观。5 段独立条每段语义清，①③ 能拿真实字节进度（file-transfer 和 copyFile 都可回传），诚实度更高。

**各段 0→100% 来源**：

| 段 | 来源 | 理由 |
|----|------|------|
| ① 下发文件中 | 受控端实话：已下载/总字节 | `filetransfer.Downloader.Download` 已知总大小，progress 回调里回传 |
| ② 校验中 | 瞬时 0→100% | MD5 计算秒级，不值得做字节进度回调 |
| ③ 备份中 | 受控端实话：已复制/总字节 | `copyFile` 改带进度版（按读写字节回传） |
| ④ 替换重启中 | 管理端推断：收到 replacing 起、已耗时/30s 线性插值；进程退出即满 | 受控端进程已死无法回传，靠 WS 断开 + 首次心跳判定 |
| ⑤ 等待版本确认中 | 管理端推断：首次心跳起算，`agentVersion==目标` 即 100%+SUCCESS | 多数瞬时跳满；异常态（重启了版本没对上）才爬，正好暴露异常 |

**为什么 ② 瞬时、④⑤ 推断可接受**：②秒级操作瞬时合理；④⑤在受控端进程死后管理端只能推断，这是协议（进程退出）的必然结果，不是设计缺陷。⑤ 的"多数瞬时跳满"恰好使该段具备"异常探测器"语义——正常时瞬间满，异常时持续爬或卡住，用户一眼看出"重启了但版本没对上"。

**备选方案（不选）**：
- *A 时间推断法*：受控端不变，管理端按阶段预期耗时推断全程条。零协议风险但进度全靠猜，①③ 明明有真实字节进度却不用，浪费。
- *C 新版自报 + 双向*：新版 exe 启动后回传"我因 OTA 重启"。最诚实但改动最大（新版 exe 协议 + 管理端识别），收益不抵风险。

**progress 消息与回包的关系（关键约束）**：progress 是**独立消息类型**，复用 `commandId` 关联 pending request。channel 识别到 `type=="progress"` 时**只更新 task 的 stagePercents，不调用 future.complete**——否则 `dispatchAndWait` 会提前返回拿不到最终 `replacing` Result。这点是协议改动的核心，实现期务必守住。

### D3. 审计覆盖：丙·混合（拦截器兜底 + @Audited 语义）

**选**：新增 `HandlerInterceptor` 兜底记录所有**非 GET** 写操作（零遗漏），保留 service 层 `@Audited` 补业务语义（action/targetType/targetId）。

**为什么不是纯拦截器（乙）**：拦截器只拿到 URL/方法/参数，拿不到 `AGENT_UPGRADE`/`STORAGE_POOL_DELETE` 这种业务语义 action，需求字段明确要"操作类型/具体操作"。

**为什么不是纯 @Audited（甲）**：注解靠人工铺，新加方法忘打就漏，覆盖率不可保证。需求是"任何操作都要有记录"——字面 100% 覆盖，只有全局拦截器能保证。

**为什么混合**：拦截器保覆盖率 + 通用字段（用户/时间/URL/客户端/唯一编码），`@Audited` 保语义（业务 action）。两者写同一张 `audit_log` 表。

### D4. 审计去重：c·拦截器让路

**选**：`@Audited`（`AuditAspect` @Around）写库后设 `request.setAttribute("audited.done", true)`；拦截器 `afterCompletion` 检测到该标志则跳过。一请求一记录，`@Audited` 优先。

**为什么不是 a 不去重**：表翻倍，查询复杂。

**为什么不是 b @Audited 覆盖拦截器记录**：`@Audited` 执行时删拦截器记录违反"不可删"，且 AOP 执行顺序耦合。

**为什么 c 干净**：拦截器只兜"没被 `@Audited` 接住的"写操作。`@Audited` 接住的，拦截器看到标志直接跳过，不产生重复记录。无删除，无顺序耦合（标志是"已记录"语义，不是"拦截器稍后别记"的指令）。

**执行顺序约束**：`@Audited` 是 AOP `@Around`，在 service 方法周围执行；拦截器 `afterCompletion` 在请求结束执行。Spring 默认 interceptor 的 `afterCompletion` 在 controller 返回后、AOP advice 完成后执行——`@Audited` 的 `finally{}` 块（设标志）在 `afterCompletion` 之前。但为防顺序不确定性，必要时给 `AuditInterceptor` 加 `@Order` 确保晚于 advice。**实现期需实测确认 `audited.done` 标志在拦截器读取时已设。**

### D5. 审计可见性：操作时关系快照（mentor_id_at_op）

**选**：`AuditLog` 新增 `mentor_id_at_op` 快照字段——审计记录写入时，若操作用户是学生，快照其当时所属导师（`groupId` → `research_group.mentor_id`）；若是导师/管理员，该字段为 null。导师查询审计时按快照判定：`WHERE operatorId = :me OR (operatorRole='STUDENT' AND mentor_id_at_op = :me)`。

**为什么快照而非当前关系**：审计的本质是"操作时的事实回溯"。学生换组后，其历史操作仍属当时导师管辖范畴——按当前关系判，旧导师看不到学生旧操作，历史追溯性丧失。快照 `mentor_id_at_op` 锁住操作时的归属，导师永远能看到"当时是自己学生"的全部操作，符合审计的回溯语义。

**快照取值**：
- 操作用户是 STUDENT：`mentor_id_at_op` = 该学生当时 `groupId` 对应的 `research_group.mentor_id`
- 操作用户是 MENTOR/ADMIN：`mentor_id_at_op` = null（自己即操作人，导师查询走 `operatorId = :me`）
- 孤儿学生（无 group）：`mentor_id_at_op` = null，任何导师看不到，仅管理员与自己可见

**各角色查询**：
- 管理员：全表（`findAll`）
- 导师：`WHERE operatorId = :me OR (operatorRole='STUDENT' AND mentor_id_at_op = :me)`
- 学生：`WHERE operatorId = :me`

**归档表同步**：`audit_log_archive` 镜像含 `mentor_id_at_op`，归档后导师历史查询跨表仍能按快照过滤（合并查询两表都用同一 WHERE）。

**为何不存"学生当时的全部组成员关系"**：只快照导师 id 即够判定可见性（导师查的是"我的学生"），无需快照整个组。存 mentor_id 单字段，简单且足够。

**实现落点**：`AuditAspect`/`AuditInterceptor` 写库前查操作用户的 `groupId` → `mentor_id` 填 `mentor_id_at_op`；查询端 `AuditLogRepository` 加按 `mentor_id_at_op` 过滤的方法。

### D6. 审计不可变：PG 触发器锁应用账号，不锁 DBA

**选**：Flyway 新增 `BEFORE UPDATE`/`BEFORE DELETE` 触发器，`RAISE EXCEPTION 'audit_log 不可修改或删除'`。锁运行期应用账号（含 admin），DBA 归档走 `DISABLE TRIGGER` 运维路径。

**为什么不用 JPA 层约束**：JPA `@CreationTimestamp` 只防 JPA 更新 `created_at`，整行 UPDATE、直接 SQL、其他事务全不拦。合规要求"不可删改"必须 DB 级。

**为什么触发器而非 REVOKE 权限**：REVOKE 应用账号的 UPDATE/DELETE 权限更硬，但 Flyway/JPA schema 管理和 Hibernate 二级写入可能依赖写权限，REVOKE 易引连锁问题。触发器 RAISE 更精准——只挡对 `audit_log` 的改删，不影响其他权限。

**DBA 归档怎么过触发器**：归档脚本以维护角色执行 `SET session_replication_role = replica`（会话级禁用所有触发器）或 `ALTER TABLE audit_log DISABLE TRIGGER ... ` → INSERT SELECT 归档 → DELETE → ENABLE。这条不写应用代码，作为 Runbook 记录，应用账号调不到。

### D7. 审计归档：30 天热表 + 每月归档 + 独立进程 + 合并查询

**选**：近 30 天留 `audit_log`（热表，查询主表）；每月归档 30 天前记录至 `audit_log_archive`（结构同 `audit_log`）。前端审计页时间范围跨归档边界时 UNION 两表结果，按 `createdAt` 统一排序，用户无感知。

**归档调度（独立进程，不阻塞应用）**：归档**不进应用进程**（避免阻塞业务请求、避免与应用共用 DB 账号被触发器拦）。归档以独立可执行单元（独立 JAR 或 SQL 脚本）运行，使用**独立 DB 维护凭据**（非应用账号），该凭据会话级 `SET session_replication_role = replica` 禁用触发器后执行 `INSERT INTO audit_log_archive SELECT ... WHERE created_at < now()-30d` → `DELETE FROM audit_log WHERE created_at < now()-30d`。调度由 OS 级调度（Linux cron / Windows 任务计划）按月触发，应用进程完全不参与，零阻塞。

**为什么不进应用 @Scheduled**：SpringBoot `@Scheduled` 与应用共用线程池和 DB 账号——共用账号会被触发器 RAISE 拦死 DELETE；占用应用线程池阻塞业务请求；应用重启会丢调度状态。独立进程 + 独立凭据 + OS 调度三者解耦，归档挂了不影响应用，应用重启不影响归档。

**为什么 30 天热表**：审计查询高频且带筛选（操作类型/用户/时间），热表保持小（30 天量级）保证查询性能。归档表只在大时间范围查询时触碰。

**合并查询实现**：`AuditLogRepository` 加 `findByOperatorIdInAndCreatedAtBetween`（热表）+ `AuditLogArchiveRepository` 同名方法（归档表），Controller 按时间范围判断是否需查归档表（结束时间 < now-30d 才纯查归档表；跨边界则两表都查后内存合并排序）。归档表实体 `AuditLogArchive` 镜像 `AuditLog`，独立 repository。

### D8. 受控端日志：rotating writer + 本地清理 + agent.log 命令

**选**：受控端新增 lumberjack-style rotating writer，落 `<storageRoot>/log/agent-YYYY-MM-DD.log`，按日分割，启动+定时扫 `log/` 删 `mtime < now-30d`。存储池根目录未设时回退 `%LOCALAPPDATA%/Nexcompute/log/`，待根目录设置后迁移。新增 `agent.log` 命令：管理端按日期范围拉取日志文件列表与内容。

**为什么不用现成 lumberjack 库**：可用，但平台受控端零外部依赖原则（现仅 Fyne/gorilla/Docker SDK），自写一个按日分割 writer 更可控，逻辑简单（`os.OpenFile` 按 `time.Now().Format("2006-01-02")` 拼名、跨日检测切文件）。

**为什么根目录未设回退 LOCALAPPDATA**：受控端启动时根目录未必已设（首次运行），但日志必须立即可写（启动日志、连接日志），不能等用户设根目录。回退 LOCALAPPDATA 保证启动即有日志；根目录设置后，writer 切换到 `<storageRoot>/log/`，旧日志不自动迁移（LOCALAPPDATA 的旧日志保留到自然过期或用户手清）——不迁移是刻意：迁移增加复杂度且旧日志价值低。

**为什么日志元数据不入库**：日志是受控端本地文件，按需现拉现返回，不入库避免元数据与文件不同步。管理端 `agent.log` 命令让受控端列目录返回文件列表（文件名含日期即元数据），按需下载内容。

**agent.log 命令协议**：受控端 `log_handlers.go` 处理 `agent.log`，payload `{date, tailLines}` 或 `{file, download}`。列目录返回 `[{name, size, modTime}]`；读内容返回尾 N 行（默认 500，避免大文件回传）或经 file-transfer 下载整文件（管理端用，受控端复用既有 `downloader` 反向上传？否——file-transfer 是管理端→受控端方向。**受控端→管理端传日志整文件需新增反向传输或用 WS 流式回传**）。**实现期需定：整文件下载走 WS 分块回传（Result.output 装不下大文件）还是新增反向 file-transfer。倾向 WS 分块回传尾 N 行 + 大文件用尾行足够，不做整文件下载，降低协议复杂度。**

### D9. 中文路径：判非 ASCII + 提示异常

**选**：启动 `os.Executable()` 路径 + 选根目录路径，遍历字符 `if r > 127` 即非 ASCII，拒绝并提示"路径含非英文字符，不支持"。

**为什么非 ASCII 而非仅汉字**：日/韩/俄文路径同样致 cmd `.bat`/PowerShell/Docker 在 ANSI 代码页下行为诡异（OTA `updater.bat` 注释已点明"cmd.exe 按 ANSI 解析 .bat，中文字节错位"）。判非 ASCII 更广更稳，提示文案说"非英文字符"即可。

### D10. 选根目录首次也索密码

**选**：spec 由"首次设置免密、修改需密码"改为"设置/修改均需本地管理员密码"。

**为什么更严**：需求明确"点击选择文件夹时就索要密码"。且首次设置根目录是高权操作（决定所有存储池落点），与"修改"同等敏感，统一索密码更一致。

### D11. 审计日志丰富查询排序筛选

**选**：审计查询界面提供多维度筛选 + 多列排序 + 模糊匹配。筛选维度覆盖时间范围、操作人（ID/姓名/角色）、操作类型、目标类型/ID、结果、客户端信息、导师归属（mentorIdAtOp）、唯一编码（operationNo）。模糊匹配对 content/errorMessage/operatorName/targetId/clientInfo 用 LIKE；operationNo 精确。多列排序支持 createdAt（默认倒序）、operatorId、action、result、targetType，列头切换升降。跨归档边界合并查询同样支持全部筛选/排序/模糊。

**为什么 LIKE 而非 PG 全文索引（tsvector）**：审计查询频率不高（管理员/导师偶尔回溯，非高频 OLTP），热表 30 天 + 字段索引足够。content 是参数 JSON 快照，LIKE 搜 JSON 文本简单直接；tsvector 要维护词典与索引、加 `tsvector` 列、触发器同步，复杂度收益不匹配。将来若查询变高频或要中文分词再升级。

**索引补充**：现有 `operator_id`/`created_at`/`action` 索引；新增 `mentor_id_at_op`（导师按快照查询走它）、`result`、`target_type`、`operation_no`（唯一编码精确）索引。归档表镜像同样索引。

**查询不产生审计**：审计查询接口（GET）本身不记审计（D3 已定只记写），避免管理员查审计产生自递归噪声。

### D12. 审计开关（完全停记）+ 开关操作恒审计

**选**：系统提供审计开关，存系统配置表，默认开。关闭后 `AuditInterceptor` 与 `AuditAspect` 检查开关，关则直接 return 不写库——完全停记（范围1）。**护栏：开关自身的切换操作恒被审计**，即使审计关闭，"管理员开启/关闭审计"仍记录，防管理员借关审计掩盖痕迹。关闭前端二次确认。

**为什么范围1（完全停记）而非范围2（只停拦截器兜底）**：需求是"管理员可以设置是否开启审计"——字面即整体开关。范围2（只停拦截器、@Audited 仍记）会让"任何操作有记录"的需求在关闭期间破裂且语义含糊（哪些算关键？）。范围1干脆：关了就不记，清清楚楚。

**为什么完全停记仍合规可接受**：开关默认开 + 切换恒审计 + 二次确认，构成护栏——管理员要偷偷关审计做坏事，"关闭审计"本身留痕，事后可查谁何时关的。这是合规功能带开关的通行做法（AWS CloudTrail 可关，但关 CloudTrail 的操作仍被 Account Activity 记录）。

**与不可删改的正交性**：触发器（D6）防的是改/删**已存**记录；开关防的是**还要不要记新的**。关了只是不写新记录，旧记录仍触发器保护。两者正交，不冲突。

**实现**：开关存 `system_config` 表（key=`audit.enabled`，value=`true/false`）。`AuditInterceptor`/`AuditAspect` 写库前查缓存开关状态。开关切换走专用 controller，该 controller 的 `@Audited` 用特殊标记（如 `@Audited(force=true)`）**绕过开关检查恒记**——这是"恒审计"的实现关键：普通 @Audited 受开关影响，force 标记的不受。

**风险**：开关关闭期间操作无记录，合规追溯丧失——已由二次确认 + 切换恒审计护栏缓解；spec 明确警示。

## Risks / Trade-offs

- **[R1] progress 消息被误判为回包** → channel 分支必须只识别 `type=="progress"`、只更新 stagePercents、**绝不 future.complete**。实现期加单测验证：发 progress 后仍能收到最终 Result。
- **[R2] `audited.done` 标志时序** → `@Audited` finally 设标志须在拦截器 afterCompletion 之前。必要时 `@Order` 强制顺序；实现期实测。
- **[R3] PG 触发器锁死运维** → 触发器只锁应用账号，DBA 走 `session_replication_role=replica` 归档；Runbook 明确归档步骤，应用代码不接触归档 DELETE。
- **[R4] 归档表与热表 UNION 性能** → 只在时间范围跨 30 天边界时查归档表，纯热表查询不走归档；归档表加 `operator_id`/`created_at`/`action` 索引。
- **[R5] 受控端日志回退 LOCALAPPDATA 旧日志残留** → 不迁移，旧日志自然过期或手清；文档标注，非缺陷。
- **[R6] 大日志整文件下载协议未定** → 已决：不做整文件下载，仅 WS 回传尾 N 行（默认 500）。若后续要整文件，新增反向 file-transfer（推迟）。
- **[R7] 审计可见性按"操作时快照"判** → 已决（D5）：`mentor_id_at_op` 快照操作时归属，导师永远能看到"当时是自己学生"的操作。快照取值依赖操作时查 `groupId→mentor_id`，写库前多一次轻量查询（可缓存用户→导师映射）。
- **[R8] ④替换重启段推断的 30s 预期不准** → 已决：30s 预期采纳，慢机器可能 >30s 致进度条卡 100% 但心跳未到。可接受（卡住比假完成诚实）；必要时调大预期或改"不设上限，靠 WS 断开判定段完成"。

## Migration Plan

1. **DB（Flyway V27）**：`audit_log` 加 `operation_no`/`client_info`/`mentor_id_at_op` 列（nullable，存量回填 null）；建 `audit_log_archive` 表（镜像含 `mentor_id_at_op`）；建 `BEFORE UPDATE`/`BEFORE DELETE` 触发器。触发器建后立即生效，应用账号无法改删审计。
2. **后端**：部署 `AuditInterceptor` + `AuditAspect` 增强（含 `mentor_id_at_op` 快照填充）+ 三角色可见性（按快照过滤）+ `AgentLogController` + OTA 进度 channel 分支。`application.yml` 超时改 180000。
3. **受控端**：发新版（含 logging writer + agent.log handler + upgrade progress 回传 + 中文路径检查 + 选根索密码 + 双排 GUI）。**鸡生蛋**：受控端新版要靠 OTA 下发，而 OTA 进度修复也在新版里——首次部署靠手动覆盖或既有 OTA（旧 OTA 会误判失败但实际成功，新版起来后正常）。
4. **前端**：部署审计页 + 受控端日志页 + OTA 进度面板 + 401 提示等 UX 改动。
5. **归档独立进程**：交付独立归档脚本/JAR + 独立 DB 维护凭据 + OS 调度配置（cron/Windows 任务计划）+ Runbook。不进应用进程。
6. **回滚**：DB 触发器可 `DROP TRIGGER` 回滚（但审计不可变是合规要求，回滚需评审）；后端/前端/受控端各自独立回滚。

## Resolved Questions

- **Q1 审计可见性按"当前关系"还是"操作时快照"** → 已决：**操作时快照**（D5）。`mentor_id_at_op` 快照操作时导师归属，学生换组后旧导师仍可见其旧操作，保历史追溯性。
- **Q2 受控端日志整文件下载是否要做** → 已决：**不做**，仅尾 N 行（R6）。
- **Q3 ④替换重启段 30s 预期是否够** → 已决：**够，采纳**（R8）。
- **Q4 审计归档的调度归属** → 已决：**独立进程 + 独立 DB 凭据 + OS 调度**（D7），不进应用进程，零阻塞，独立挂掉不影响应用。
