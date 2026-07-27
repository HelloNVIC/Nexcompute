## Context

平台已有完整事件触发表面，邮件为新增第 4 通道。关键现状（经后端代码确认）：

- **7 触发点全部经 `@Audited`**：`USER_CREATE`/`USER_DISABLE`/`MACHINE_ALLOCATE`(_GROUP)/`MACHINE_DEALLOCATE`(_GROUP)/`STORAGE_POOL_MIGRATE`(_CONFIRM)/`IMAGE_SHARE`(+`IMAGE_SET_VISIBILITY`)/`CONTAINER_SHARE`(+`CONTAINER_UNSHARE`)。`@Audited` 在 `finally` 触发（成功失败均记审计）。
- **仅部分经 `NotificationService.notify()`**：存储池迁移（`StoragePoolService:176,290`）、容器创建/生命周期（`ContainerService:141,307`）经 notify；容器 share/unshare、镜像 share、注册、禁用、实例分配/撤销 **不经 notify**（仅审计）。
- **自助注册无审计无通知**：`AuthService.register()` 既无 `@Audited` 也无 `notify()`，为唯一纯新触发点。
- **User 已有 `email` 字段**（`domain/User.java:39`）；`UserRole={ADMIN,MENTOR,STUDENT}`。
- **`SystemConfig` 键值表已存在**（`config_key`/`config_value` TEXT），存 `audit.enabled` 等，适合存 SMTP 配置与触发键开关。
- **`SystemInfoController.update` 已是 ADMIN-only**（`SystemInfoController.java:32`），可作 SMTP 编辑鉴权模板。
- **`application.yml`** 用 `${ENV:default}` 模式；Flyway enabled，`ddl-auto=validate`（schema 仅经 Flyway）；当前 V27。
- **`build.gradle.kts`** 仅 `starter-web`/`starter-websocket`，**无 mail starter**（依赖缺口）。
- **既有异步先例**：`AuditService` 异步写库，可仿 `@Async` 模式隔离邮件发送。

## Goals / Non-Goals

**Goals:**
- 8 类事件成功后异步邮件提醒，不阻断业务事务。
- 管理员全局逐项开关（默认全开）；学生/导师逐项 opt-out（注册/禁用/启用强制除外）。
- SMTP 配置文件默认 + 系统信息页可编辑 + DB 持久化 + 运行时刷新 sender + 测试发送（手动输入收件人）。
- 镜像权限变化通知被共享个人 + 原可见用户（去重）。
- 邮件正文嵌入系统 Logo（PNG + CID 内联,跨客户端稳定;管理员可上传替换）。
- 品牌名与落款管理员可配置（`system_config`）。
- 正文含详细操作日志与文字提醒（与审计同源）。
- 发送结果可查（`email_log`）。

**Non-Goals:**
- 不做邮件模板 i18n（先中文，后续按需）。
- 不做发送失败自动重试（仅记 `email_log` 供人工排查；后续可加）。
- 不做 SMTP 密码加密存储（用户明确不需要；配置文件 `.gitignore` + GET 脱敏）。
- 不复用 `NotificationService.notify()`（邮件为独立通道，注册/禁用不进收件箱）。
- 不改 `@Audited` 切面（邮件走独立显式调用，仅在业务成功路径）。

## Decisions

### D1. 邮件走独立 EmailService.sendAt 显式调用（Path C）
- **Decision**：新增 `EmailService.sendAt(EmailTrigger trigger, User recipient, Map ctx)`，在 8 触发点的**业务成功路径**末尾显式调用。策略（全局开关 + 用户偏好 + 模板渲染 + 异步发送 + 日志）集中于 `EmailService` 内部，触发点仅传 trigger/recipient/ctx。
- **Alternatives**：(a) 复用 `NotificationService.notify()` 加第 4 通道--rejected，注册/禁用进收件箱语义怪、且 5 个触发点本不经 notify 需补调用，不如直接独立；(b) 新 `@EmailTrigger` AOP 切面仿 `@Audited`--rejected，需 SpEL 解析"受影响用户"（非操作者），而各触发点已加载 recipient User 对象，显式传更直接；且 `@Audited` 在 `finally` 成功失败均触发，邮件需仅成功路径。
- **Rationale**：与既有 `notify()` 显式调用惯例一致；recipient 已在各触发点加载；邮件仅成功路径发送天然契合显式调用位置。

### D2. 8 触发键枚举
- **Decision**：新增 `EmailTrigger` 枚举：`USER_REGISTERED`/`USER_DISABLED`/`USER_ENABLED`/`INSTANCE_ALLOCATED`/`INSTANCE_DEALLOCATED`/`STORAGE_POOL_MIGRATED`/`IMAGE_PERMISSION_CHANGED`/`CONTAINER_PERMISSION_CHANGED`。其中 `USER_REGISTERED`/`USER_DISABLED`/`USER_ENABLED` 标记 `mandatory=true`（用户不可 opt-out）。
- **Rationale**：枚举便于扩展（用户提"等"）；`mandatory` 标志集中表达"注册/禁用/启用除外"。`USER_ENABLED` 与 `USER_DISABLED` 对称，启用账户亦为强制提醒。

### D3. 偏好两层模型
- **Decision**：
  - 管理员全局：`system_config` 键 `email.trigger.<ENUM>.enabled`（默认 true），共 7 键。
  - 用户偏好：新建 `user_email_pref(user_id, trigger_key, enabled, updated_at)`，默认 true（即表中只记用户主动关闭的 3-7 项）；`mandatory` 触发键不入表、偏好查询时强制视为 true。
  - 发送判定：`全局开关 == true && (mandatory || 用户偏好 != false)`。
- **Alternatives**：用户偏好也存 `system_config` 按 user 拼键--rejected，键爆炸、查询低效。

### D4. SMTP 配置：配置文件默认 + DB 持久化
- **Decision**：
  - 默认值经 `application.yml` `nexcompute.email.*`（`${EMAIL_FROM:cufel@cufe.edu.cn}` 等，`${ENV}` 可覆盖）。
  - 首启若 `system_config` 无 SMTP 键，种子入 6 键：`email.smtp.from`/`host`/`port`/`protocol`/`user`/`passwd`。
  - 运行时 `EmailService` 读 DB `system_config` 构建 `JavaMailSenderImpl`（缓存，配置变更后刷新）。
  - 管理员经"系统信息"页编辑 -> `PUT /system-info/email`（ADMIN-only）-> 写 DB -> 刷新 sender。
- **Alternatives**：纯配置文件不可运行时编辑--rejected，用户要求系统信息页可编辑；纯 DB 无默认--rejected，用户要求配置文件存默认。
- **Rationale**：配置文件满足"默认存放配置文件"，DB 满足"系统信息页可编辑运行时生效"。

### D5. 密码明文不加密 + GET 脱敏
- **Decision**：PASSWD 明文存配置文件与 DB（用户明确不需要加密）。`GET /system-info/email` 返回时 PASSWD 脱敏为 `****`（或仅返布尔"已配置"）；`PUT` 接受明文写入。配置文件真实凭据入 `.gitignore`，生产以环境变量覆盖。
- **Rationale**：用户明确不加密；脱敏防前端泄露；`.gitignore` 防凭据入库。

### D6. 异步发送 + email_log
- **Decision**：`EmailService.sendAt` 内 `@Async` 异步发送（仿 `AuditService`）；新建 `email_log(id, trigger_key, recipient_user_id, recipient_email, subject, status, error, sent_at)` 表记录每次发送。发送失败 catch + log + 写 `status=FAILED`，不抛（业务事务已提交，不受影响）。
- **Risk**：`@Async` 需 `@EnableAsync` + 线程池配置-- Mitigation：复用既有异步配置或新增 `AsyncConfig`。
- **Rationale**：SMTP 慢且不稳定，必须不阻塞业务；`email_log` 供排查送达。

### D7. 镜像权限变化通知范围
- **Decision**：`ImageService` 共享/可见性变更时，收件人为：被共享的个人 **∪** 该镜像当前所有原可见用户（按 `image_share` 表 + visibility `PUBLIC` 时全体可见用户）。收件人去重后经 `sendAtBatch` 逐个异步发送。
- **Alternatives**：仅通知被共享个人--rejected，用户明确要同时通知原可见用户。
- **Risk**：原可见用户量大时批量发送-- Mitigation：异步 + 分批；`email_log` 记录。

### D8. 系统信息页 UI 扩展
- **Decision**：`SystemInfoView.vue` 新增两区块：（1）"邮箱配置"（SMTP 6 字段 + "测试发送"按钮，PASSWD 脱敏显示）；（2）"邮件触发时机"（8 触发键全局开关表）。用户偏好另设页面/区块（学生/导师可见，注册/禁用/启用项 disabled）。
- **Rationale**：用户明确邮箱配置放管理员"系统信息"中。

### D9. 邮件正文嵌入系统 Logo（管理员可上传替换）
- **Decision**：每封邮件正文顶部嵌入系统 Logo 图片,经 **CID 内联附件**（`DataSource` + `addInline("logo", ...)` + `<img src="cid:logo" ...>`）嵌入 HTML 正文。Logo 来源分两层:
  - **默认**：既有品牌资产 `management-frontend/public/logo.svg`（侧边栏 `BasicLayout.vue:112` 已用）转 **PNG** 置于后端 `src/main/resources/email/logo.png`,作种子/兜底。
  - **管理员上传替换**：管理员可在"系统信息"页上传 Logo（`POST /system-info/email/logo`,ADMIN-only,multipart）,存 `${storage.root}/email/logo.<ext>`,文件名记 `system_config` 键 `email.brand.logo_filename`。发送时优先用已上传 Logo,无则用 classpath 默认 PNG。
  - **格式约束**：接受 PNG/JPG（拒绝 SVG--邮件不渲染）;建议尺寸 ≤200×80、文件 ≤1MB。
- **Alternatives**：(a) 直接用 SVG via CID--rejected,客户端支持差;(b) 外链 URL--rejected,需公网可达且客户端默认阻止外链图片;(c) base64 data URI--rejected,不渲染;(d) 仅固定 bundled PNG 不可换--rejected,用户要求管理员可上传修改。
- **Rationale**：CID 内联附件是邮件 Logo 跨客户端最稳方案;上传替换满足品牌可定制,默认 PNG 保证开箱即用。

### D10. 邮件品牌名与落款管理员可配置
- **Decision**：品牌名与邮件落款存 `system_config`,管理员可在"系统信息"页编辑:
  - `email.brand.name`（默认"合算 Nexcompute"）：用于邮件 shell 品牌标题、落款、主题。
  - `email.signature`（默认"- Nexcompute 管理平台\n（此邮件由系统自动发送,请勿直接回复）",多行 TEXT）：邮件落款,支持多行。
  - 首启种子默认值;`PUT /system-info/email/brand`（ADMIN-only）写库。
- **Rationale**：用户明确品牌名与落款可改;复用 `system_config` 键值表,无需新表。
- **Scope**：本变更仅邮件品牌;侧边栏 `BasicLayout.vue:113` 的"合算 Nexcompute"不在本变更范围（如需平台统一品牌另开变更）。

### D11. 邮件正文操作日志 + 文字提醒结构
- **Decision**：每封邮件正文除事件描述外,统一含两段:
  - **操作日志**：结构化呈现本次操作--操作人、操作时间、操作类型（中文 label）、操作对象、变更详情（键值或 before/after）。字段源自触发点 ctx（与审计字段同源）。
  - **文字提醒**：该事件的可执行提醒要点（如"请提前备份重要数据""迁移期间暂不可写"）,逐条列出。
  - `EmailTemplateService` 渲染时按触发键填入这两段;`email-templates-draft.md` 给出每触发键的具体文案。
- **Rationale**：用户要求正文有详细操作日志与文字提醒;操作日志与审计同源,信息一致可追溯。

## Risks / Trade-offs

- **[SMTP 慢/不稳定] -> 异步 + 日志**：`@Async` 不阻塞业务；失败记 `email_log` 不重试（后续可加）。
- **[PASSWD 明文] -> .gitignore + GET 脱敏**：用户明确不加密；防凭据入库与前端泄露。
- **[批量邮件风暴] -> 异步分批**：镜像可见性变更波及多人时异步分批发送。
- **[配置变更不生效] -> sender 刷新**：`PUT` 后刷新缓存的 `JavaMailSender`。
- **[触发点遗漏] -> 枚举 + tasks 核对**：8 触发键枚举与 tasks 一一对应，逐项验收。
- **[SVG 邮件不渲染] -> PNG + CID**：默认 PNG 经 CID 内联;管理员上传仅接受 PNG/JPG。
- **[Logo 上传滥用] -> 格式+体积校验**：上传限 PNG/JPG、≤1MB、ADMIN-only。

## Migration Plan

1. **Flyway** `V28__email_notification.sql`：
   - 新建 `user_email_pref`（`user_id`, `trigger_key`, `enabled BOOLEAN DEFAULT TRUE`, `updated_at`，联合唯一 `(user_id, trigger_key)`）。
   - 新建 `email_log`（`id`, `trigger_key`, `recipient_user_id`, `recipient_email`, `subject`, `status`, `error`, `sent_at`）。
   - `system_config` 种子：7 触发键 `email.trigger.<ENUM>.enabled=true` + 6 SMTP 键默认值 + `email.brand.name`（"合算 Nexcompute"）+ `email.signature`（默认落款）+ `email.brand.logo_filename`（空,用 classpath 默认 PNG）。
2. **Flyway** `V29__email_user_enabled_trigger.sql`：补种第 8 触发键 `email.trigger.USER_ENABLED.enabled=true`（启用账户发邮件，mandatory）。
2. **后端**：先上 `EmailService` + 配置 + 偏好端点（全局开关默认开），再在 8 触发点接入 `sendAt`。
3. **前端**：系统信息页邮箱配置 + 触发开关；用户偏好页。
4. **回滚**：Flyway 可 drop 新表；`system_config` 种子可删；触发点 `sendAt` 调用移除不影响业务（邮件为旁路）。

## Resolved Questions

- **全局开关默认值**：默认全开（V28 种 7 触发键 + V29 补种 `USER_ENABLED`，均 `email.trigger.<ENUM>.enabled=true`），管理员按需关。
- **邮件模板文案**：草案见 `email-templates-draft.md`（8 触发键主题/正文 + 通用外壳 + 占位符派生规则），验收时调。
- **"测试发送"收件人**：手动输入收件箱（`POST /system-info/email/test` 接收 `toEmail` 入参）。
- **邮件正文 Logo**：嵌入系统 Logo（既有 `logo.svg` 转 PNG，CID 内联附件，见 D9）。
