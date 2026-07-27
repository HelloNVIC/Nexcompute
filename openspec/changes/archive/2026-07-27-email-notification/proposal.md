## Why

平台已有完整的事件触发表面：7 类生命周期事件（用户注册、账户禁用、实例分配/撤销、存储池迁移、镜像权限变化、容器权限变化）均已由 `@Audited` AOP 切面捕获并记审计日志，其中部分经 `NotificationService.notify()` 走站内信 + SSE + Toast。但所有提醒仍局限于网页端被动查看，离线/未登录用户无法及时获知账户与资源变动。需新增邮件提醒通道，使关键事件可经邮箱送达；并允许管理员统一管控发送时机、学生/导师按需关闭打扰类提醒。`User.email` 字段已存在、`UserRole={ADMIN,MENTOR,STUDENT}` 与角色需求一致，无需改领域模型。

## What Changes

**邮件触发与发送**
- 新增 `EmailService.sendAt(trigger, recipient, ctx)`：内部依次查询管理员全局开关（`system_config`，默认全开）-> 用户偏好（`user_email_pref`，注册/禁用为强制不可关）-> 渲染模板 -> 异步 SMTP 发送 -> 写 `email_log`。
- 在 8 个触发点的**业务成功路径**上显式调用 `sendAt`（仿既有 `NotificationService.notify()` 显式调用惯例）。
- 邮件通道与站内信独立：注册/禁用不进收件箱，邮件为离线用户唯一可达通道。
- 正文含"操作日志"段（操作人/时间/类型/对象/变更详情，与审计同源）+ "文字提醒"段（可执行要点）；模板文案草案见 `email-templates-draft.md`，验收时调。

**邮件品牌与正文**
- 品牌名（默认"合算 Nexcompute"）与落款（多行，默认落款）存 `system_config`，管理员可在"系统信息"页编辑，用于邮件品牌标题/落款/主题。
- 系统 Logo 默认由 `logo.svg` 转 PNG 提供，管理员可上传替换（PNG/JPG、≤1MB，存 `${storage.root}/email/`），经 CID 内联附件嵌入正文顶部。

**偏好与开关**
- 管理员全局开关：`system_config` 新增 `email.trigger.<key>.enabled` 共 7 键。
- 用户偏好：新建 `user_email_pref(user_id, trigger_key, enabled)`，默认 true；仅记录用户主动关闭项。注册/禁用两项为强制，不入偏好表、查询时强制视为 true。

**镜像权限变化通知范围**
- 镜像权限变化（共享/可见性变更）同时通知被共享个人与该镜像所有原可见用户，收件人去重。

**SMTP 配置**
- 配置文件提供默认值，首次启动种子入 `system_config`（`email.smtp.from/host/port/protocol/user/passwd` 6 键）。
- 管理员可在"系统信息"页查看编辑，编辑后持久化至 DB 并刷新运行时 sender；"测试发送"按手动输入收件箱发送测试邮件。
- `GET` 接口对 PASSWD 脱敏；仅 `PUT` 接受明文。密码明文存储（不加密）。
- `build.gradle.kts` 新增 `spring-boot-starter-mail`。

## Capabilities

### New Capabilities
- `email-notification`: 8 类生命周期事件邮件提醒通道；管理员全局逐项开关（默认全开）+ 学生/导师逐项 opt-out（注册/禁用/启用除外）；SMTP 配置文件默认 + 系统信息页可编辑 + DB 持久化 + 测试发送（手动输入收件人）；镜像权限变化通知被共享个人 + 原可见用户（去重）；异步发送不阻断业务 + `email_log` 追踪；邮件正文嵌入系统 Logo（管理员可上传替换，PNG + CID 内联）+ 品牌名/落款可配置 + 操作日志/文字提醒段。

## Impact

**管理端后端（SpringBoot）**
- 新增 `EmailService`（策略判定 + 模板渲染 + 异步发送 + 日志）、`EmailTemplateService`（触发键 -> 主题/正文）、`EmailTriggerService`（全局开关与用户偏好查询）、`EmailLog` 领域 + `EmailLogRepository`、`UserEmailPref` 领域 + `UserEmailPrefRepository`、`EmailTrigger` 枚举。
- `build.gradle.kts` 增 `spring-boot-starter-mail`；`application.yml` 增 `nexcompute.email.*` 默认值（`${ENV}` 可覆盖）。
- 8 触发点显式调 `emailService.sendAt`：`AuthService.register`、`UserService.disableUser`/`enableUser`、`ResourceAllocationService` allocate/deallocate、`StoragePoolService.migrate`、`ImageService.share*`/`setVisibility`、`ContainerService.share`/`unshare`。
- `SystemInfoController` 扩 SMTP 配置 GET（脱敏）/PUT（仅 ADMIN）+ 触发键全局开关 GET/PUT + 品牌/落款 GET/PUT + Logo 上传/预览 + 测试发送；新增 `EmailPrefController`（用户偏好 GET/PUT）。

**管理端前端（Vue 3 / AntD）**
- `views/admin/SystemInfoView.vue` 增"邮箱配置"区块（SMTP 字段 + 测试发送）+"邮件触发时机"开关表+"邮件品牌"区块（品牌名/落款/Logo 上传预览）。
- 新增用户偏好页/区块（学生/导师可见，注册/禁用 disabled）。
- `api/` 新增 `email.ts`（SMTP 配置、触发开关、用户偏好、测试发送）。

**基础设施**
- Flyway `V28__email_notification.sql`：新建 `user_email_pref`、`email_log` 表；`system_config` 种子 7 触发键开关（默认 true）+ 6 SMTP 键 + `email.brand.name`/`email.signature`/`email.brand.logo_filename` 默认值。`V29__email_user_enabled_trigger.sql` 补种第 8 触发键 `email.trigger.USER_ENABLED.enabled`（启用账户发邮件，mandatory）。
