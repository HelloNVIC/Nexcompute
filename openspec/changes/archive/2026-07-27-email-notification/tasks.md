## 1. 数据库迁移（Flyway）

- [x] 1.1 新建 `user_email_pref` 表（`user_id BIGINT`, `trigger_key VARCHAR(50)`, `enabled BOOLEAN DEFAULT TRUE`, `updated_at TIMESTAMP`，联合唯一索引 `(user_id, trigger_key)`）
- [x] 1.2 新建 `email_log` 表（`id BIGSERIAL`, `trigger_key VARCHAR(50)`, `recipient_user_id BIGINT`, `recipient_email VARCHAR(100)`, `subject VARCHAR(200)`, `status VARCHAR(20)`, `error TEXT`, `sent_at TIMESTAMP`）
- [x] 1.3 `system_config` 种子 7 触发键开关 `email.trigger.<ENUM>.enabled=true`
- [x] 1.4 `system_config` 种子 6 SMTP 键默认值（from/host/port/protocol/user/passwd，来自 `application.yml` 默认）
- [x] 1.5 `system_config` 种子 `email.brand.name`（"合算 Nexcompute"）+ `email.signature`（默认落款）+ `email.brand.logo_filename`（空，用 classpath 默认 PNG）
- [x] 1.6 验证迁移可正向执行且幂等
- [x] 1.7 `V29__email_user_enabled_trigger.sql` 补种第 8 触发键 `email.trigger.USER_ENABLED.enabled=true`（启用账户发邮件，mandatory）

## 2. 后端依赖与配置（D4/D5/D6）

- [x] 2.1 `build.gradle.kts` 增 `spring-boot-starter-mail`
- [x] 2.2 `application.yml` 增 `nexcompute.email.*` 默认值（`${EMAIL_FROM:cufel@cufe.edu.cn}` 等，`${ENV}` 可覆盖）
- [x] 2.3 `AsyncConfig`（`@EnableAsync` + 线程池，或复用既有异步配置）
- [x] 2.4 验证 mail starter 引入后启动无冲突（compileJava 通过；application.yml 用 nexcompute.email.* 而非 spring.mail.*，MailSenderAutoConfiguration 不创建自动 JavaMailSender bean，与后续 EmailService 自建 JavaMailSenderImpl 无冲突）

## 3. 领域与仓库（D2/D3/D6）

- [x] 3.1 新增 `EmailTrigger` 枚举（8 值 + `mandatory` 标志，`USER_REGISTERED`/`USER_DISABLED`/`USER_ENABLED` mandatory=true）
- [x] 3.2 新增 `UserEmailPref` 领域 + `UserEmailPrefRepository`（`findByUserIdAndTriggerKey`、upsert）
- [x] 3.3 新增 `EmailLog` 领域 + `EmailLogRepository`
- [x] 3.4 新增 `EmailTriggerService`：全局开关查询（`system_config`）+ 用户偏好查询（mandatory 强制 true）+ 发送判定 `shouldSend(trigger, user)`
- [x] 3.5 验证发送判定逻辑（全局关->不发；mandatory->强制发；用户关且非 mandatory->不发）

## 4. EmailService 与模板（D1/D6/D7/D9/D10/D11）

- [x] 4.1 将 `management-frontend/public/logo.svg` 转为 PNG，可以参考`controlled-agent\tools\genicon\main.go`，生成后不需要检查，人工检查即可。置于后端 `src/main/resources/email/logo.png`（默认兜底）
- [x] 4.2 新增 `EmailTemplateService`：按 `email-templates-draft.md` 草案实现 8 触发键主题/正文 + 通用外壳（含 `${brandName}`/`${signature}` 占位、操作日志段、文字提醒段，正文顶部 `<img src="cid:logo">` + 品牌标题）
- [x] 4.3 新增 `EmailService.sendAt(trigger, recipient, ctx)`：判定 -> 渲染（读 `email.brand.name`/`email.signature`/`email.brand.logo_filename`）-> `@Async` SMTP（`addInline("logo", ...)` 内联附件）-> 写 `email_log`
- [x] 4.4 Logo 文件解析：`email.brand.logo_filename` 非空用 `${storage.root}/email/` 下已上传文件，否则 classpath 默认 PNG
- [x] 4.5 `EmailService` 启动/配置变更时构建并缓存 `JavaMailSenderImpl`（读 `system_config` SMTP 键），`PUT` 后刷新
- [x] 4.6 新增 `sendAtBatch(trigger, recipients, ctx)`（镜像可见性变更多收件人，去重）
- [ ] 4.7 验证异步发送不阻塞业务、失败写 `email_log` 不抛、Logo 经 CID 在主流客户端正常渲染、品牌名/落款/操作日志/提醒正确填充

## 5. 8 触发点接入 sendAt（D1）

- [x] 5.1 `AuthService.register` 成功后 `sendAt(USER_REGISTERED, user, ctx)`
- [x] 5.2 `UserService.disableUser` 成功后 `sendAt(USER_DISABLED, user, ctx)`
- [x] 5.2b `UserService.enableUser` 成功后 `sendAt(USER_ENABLED, user, ctx)`
- [x] 5.3 `ResourceAllocationService` allocate/allocateGroup 成功后 `sendAt(INSTANCE_ALLOCATED, user, ctx)`
- [x] 5.4 `ResourceAllocationService` deallocate/deallocateGroup 成功后 `sendAt(INSTANCE_DEALLOCATED, user, ctx)`
- [x] 5.5 `StoragePoolService.migrate` 成功后 `sendAt(STORAGE_POOL_MIGRATED, owner, ctx)`
- [x] 5.6 `ImageService.share*`/`setVisibility` 成功后 `sendAtBatch(IMAGE_PERMISSION_CHANGED, {被共享个人 ∪ 原可见用户}, ctx)`（D7 去重）
- [x] 5.7 `ContainerService.share`/`unshare` 成功后 `sendAt(CONTAINER_PERMISSION_CHANGED, sharedTo, ctx)`
- [x] 5.8 验证各触发点仅业务成功路径发送、ctx 含必要上下文（资源名/操作人等）

## 6. SMTP/品牌/Logo/落款 配置端点（D4/D5/D9/D10）

- [x] 6.1 `SystemInfoController` 增 `GET /system-info/email`（PASSWD 脱敏返回 `****`）
- [x] 6.2 `SystemInfoController` 增 `PUT /system-info/email`（ADMIN-only，接受明文写入 `system_config`，刷新 sender）
- [x] 6.3 `SystemInfoController` 增 `GET/PUT /system-info/email/triggers`（8 触发键全局开关）
- [x] 6.4 `SystemInfoController` 增 `GET/PUT /system-info/email/brand`（品牌名 + 落款，ADMIN-only）
- [x] 6.5 `SystemInfoController` 增 `POST /system-info/email/logo`（上传 Logo，ADMIN-only，PNG/JPG、≤1MB，存 `${storage.root}/email/`，记 `email.brand.logo_filename`）+ `GET`（预览当前 Logo）
- [x] 6.6 新增"测试发送"端点 `POST /system-info/email/test`（手动输入收件人，发测试邮件，含 Logo/品牌/落款/操作日志）
- [x] 6.7 验证非管理员不可 PUT/上传、PASSWD 不回显、SVG/超限 Logo 被拒、PUT 后配置刷新生效

## 7. 用户偏好端点（D3）

- [x] 7.1 新增 `EmailPrefController`（或在 `NotificationController`）`GET/PUT /api/me/email-prefs`
- [x] 7.2 注册/禁用两项返回 `mandatory=true`（前端 disabled）
- [x] 7.3 验证用户关闭某项后不再收到该类邮件、其他用户不受影响

## 8. 前端（D8/D9/D10）

- [x] 8.1 `SystemInfoView.vue` 增"邮箱配置"区块（SMTP 6 字段，PASSWD 脱敏显示，"测试发送"按钮手动输入收件人）
- [x] 8.2 `SystemInfoView.vue` 增"邮件触发时机"开关表（8 触发键）
- [x] 8.3 `SystemInfoView.vue` 增"邮件品牌"区块（品牌名输入 + 落款多行文本 + Logo 上传组件 + 当前 Logo 预览）
- [x] 8.4 新增用户偏好页/区块（学生/导师可见，注册/禁用 disabled）
- [x] 8.5 `api/email.ts` 新增（SMTP 配置、触发开关、品牌/Logo/落款、用户偏好、测试发送）
- [ ] 8.6 验证管理员可编辑 SMTP/触发开关/品牌名/落款、上传 Logo、学生/导师可关 5 项、测试发送可发

## 9. 端到端验证

- [ ] 9.1 注册新用户 -> 收到注册邮件（用户不可关）
- [ ] 9.2 禁用用户 -> 收到禁用邮件（用户不可关）
- [ ] 9.2b 启用用户 -> 收到启用邮件（用户不可关）
- [ ] 9.3 分配/撤销实例 -> 收到邮件；用户关闭后不再收
- [ ] 9.4 存储池迁移 -> 收到邮件
- [ ] 9.5 镜像共享 -> 被共享人 + 原可见用户均收到（去重）
- [ ] 9.6 容器共享/取消 -> 收到邮件
- [ ] 9.7 管理员关某全局开关 -> 该类无人收
- [ ] 9.8 SMTP 配置编辑后生效、PASSWD 不回显
- [ ] 9.9 发送失败 -> `email_log` 记 FAILED、业务不受影响
- [ ] 9.10 邮件正文 Logo 经 CID 内联在主流客户端（Gmail/Outlook/网页端）正常渲染；管理员上传新 Logo 后邮件用新 Logo
- [ ] 9.11 "测试发送"按手动输入收件箱发送测试邮件、含 Logo/品牌/落款/操作日志
- [ ] 9.12 管理员改品牌名/落后，后续邮件用新品牌名/落款；正文含操作日志与文字提醒段
- [ ] 9.13 SVG/超限 Logo 上传被拒
- [x] 9.14 `openspec validate email-notification --strict` 通过
