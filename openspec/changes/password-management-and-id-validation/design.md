## Context

Nexcompute 平台已有完整认证体系（`openspec/specs/access-control/spec.md`）：`app_user` 表（`username`=工号/学号 unique、`password_hash` BCrypt、`email`、`student_id`、`role`、`status`）；`AuthController` 暴露 `/auth/login`、`/auth/register`、`/auth/mentor-register`、`/auth/admin-register`、`/auth/register/validate`（公开，WIP）、`/auth/me`（GET/PUT）；`SecurityConfig.permitAll` 已放行注册类公开端点 + nas/newapi 公开注册；`PasswordEncoder`=BCrypt；`@Audited` AOP 切面（`force=true` 恒审计）；`SecurityUtils.getCurrentUserId()/getCurrentRole()`；`EmailService` 有 `sendNasMail(triggerKey,...)` 风格通道（任意 `trigger_key` 字符串 + `sendMime` + 写 `email_log`，不依赖 `EmailTrigger` 枚举/用户偏好）；`systemConfigRepository` 运行时配置；`@Scheduled` 已用（`HeartbeatTimeoutScheduler`/`NasAllocationScheduler`）；Flyway 当前 V33，`ddl-auto=validate`，SQL 禁 `${`。前端 Vue3+AntD，公开路由 `meta.public:true`，`ProfileView.vue` 已有「编辑个人信息」+ 邮件偏好卡片，三个注册页 onMounted 调 `validateRegisterLink`，`api/auth.ts` 已有 `validateRegisterLink` 封装，`utils/request.ts` 的 `http.get/post/put` + `utils/clipboard.ts`。

三块新能力均属认证域，复用既有 BCrypt / 邮件 / 调度 / 审计 / 邀请校验基础设施，不引入新外部依赖（无 Redis）。

## Goals / Non-Goals

**Goals:**
- 登录态用户在「用户信息」自助改密：校验旧密码 + 新密码强度 + 恒审计。
- 忘记密码自助找回：工号/学号 -> 邮箱验证码 -> 重置，全程公开免鉴权，防枚举、防暴破、限频。
- 邀请注册页工号/学号即时可用性校验（邀请门控，提交前反馈）。
- 全栈 SpringBoot+Vue，并入 `nexcompute` 库（单 Flyway），复用既有 `app_user` / 邮件 / 审计 / 邀请校验。

**Non-Goals:**
- 不做 JWT 会话失效/令牌吊销（状态 JWT 无 blocklist，改密/重置后既有 token 仍有效至过期；记为风险，非本期）。
- 不做 IP 级限流（平台内网，仅按用户名限频）。
- 不做短信/二次验证/2FA。
- 不为忘记密码邮件新增 `EmailTrigger` 枚举项或用户偏好开关（走 `sendNasMail` 风格系统邮件）。
- 不做管理员重置密码的改动（既有 `UserService.resetPassword` 保留）。
- 不做账号找回（无邮箱/邮箱失效的账号走管理员线下重置）。

## Decisions

### D1. 归属 access-control 既有 capability，不新设 capability
- **Decision**：三块能力作为 `access-control` 的需求扩展（ADDED Requirements delta），不新建 `password-management` capability。
- **Alternatives**：新建 `password-management` capability--rejected，密码改/重置是核心认证行为，与既有「登录会话失效与失败提示」「用户编辑与课题组归属管理（含管理员重置密码）」同域，分拆会割裂认证 spec。
- **Rationale**：access-control 已涵盖登录/注册/密码/会话；扩展保持单一认证真相源。

### D2. OTP 持久化用新表 password_reset_otp（BCrypt 哈希码），非内存/Redis
- **Decision**：Flyway `V34` 建 `password_reset_otp`（`id`/`username`/`code_hash`/`expires_at`/`consumed_at`/`attempt_count`/`created_at` + 索引）。6 位数字码用既有 `passwordEncoder`（BCrypt）哈希存 `code_hash`，不存明文。
- **Alternatives**：(a) 内存 ConcurrentHashMap--rejected，重启丢失、多实例不一致；(b) Redis--rejected，项目无 Redis，引入重；(c) 复用 `email_log`--rejected，语义不符、无消费/限频状态。
- **Rationale**：单 PostgreSQL 已就绪，持久化跨重启；BCrypt 哈希与平台密码哈希同算法同 bean，防库泄露后码可被离线暴破的窗口最小化。

### D3. 验证码邮件走 sendNasMail 风格通道（trigger_key=PASSWORD_RESET_CODE），不扩 EmailTrigger 枚举
- **Decision**：`EmailService` 新增 `sendPasswordResetCode(toEmail, username, fullName, code, expireMinutes)`，仿 `sendNasMail`：内联 HTML + Logo CID + 品牌/落款读 `system_config`，`trigger_key="PASSWORD_RESET_CODE"` 写 `email_log`，`@Async` 不阻断。
- **Alternatives**：新增 `EmailTrigger.PASSWORD_RESET` 枚举 + 模板 + 用户偏好开关--rejected，验证码是系统安全邮件非用户可关通知，加枚举会触发 `system_config` 开关键 + `user_email_pref` 迁移 + 模板表，过度工程。
- **Rationale**：与 nas/newapi 账号通知同通道同模式（`NEWAPI_ACCOUNT_ACTIVATED` 等亦非枚举），最小改动。

### D4. send-code 防枚举：恒返回统一成功，账号不存在不发邮件
- **Decision**：`POST /auth/forgot-password/send-code` 无论账号是否存在/是否绑定邮箱/是否被禁用，均返回统一 `{ message: "若账号存在，验证码已发送至其绑定邮箱" }`；仅当账号存在 + `status=ACTIVE` + `email` 非空时实际生成码并异步发送。
- **Alternatives**：返回脱敏邮箱（`z**@example.com`）提示用户--rejected，成功返回脱敏邮箱、失败返回通用语会泄露账号存在性（响应形状不同）；如统一返回脱敏邮箱则需为不存在账号伪造邮箱，欺骗用户。
- **Rationale**：防工号/学号枚举是安全基线；UX 损失（用户不知发往哪个邮箱）可接受，账号绑定邮箱在注册/编辑时已由用户自行确认。

### D5. check-username 需有效邀请令牌（复用 validateRegistrationLink），防开放枚举
- **Decision**：`GET /auth/register/check-username?token=&linkType=&studentId=` 先调 `validateRegistrationLink(token, linkType)`，`valid=false` 直接返回 `available=false, reason="邀请链接无效"`（不暴露存在性）；`valid=true` 再 `existsByUsername(studentId)` 返回 `{ available }`。
- **Alternatives**：纯公开 `check-username?studentId=` 免令牌--rejected，开放工号枚举（内网仍有风险，且与「邀请门控注册」语义相悖）。
- **Rationale**：注册本就邀请门控，可用性校验绑定同一令牌最自然；令牌失效时不反馈存在性。

### D6. 改密恒审计（@Audited force=true），targetId 经 SpEL T(SecurityUtils)
- **Decision**：`AuthController.changePassword` 标 `@Audited(action="USER_CHANGE_PASSWORD", targetType="USER", targetIdExpr="T(com.nexcompute.management.security.SecurityUtils).getCurrentUserId()", force=true)`。操作人经 `AuditContextResolver.resolve()` 自动捕获（=当前登录用户）。
- **Alternatives**：(a) 不审计（同 `updateProfile` 现状）--rejected，改密是安全敏感操作须恒审计；(b) 服务层取 userId 作参数供 targetIdExpr 引用--可行但污染签名，SpEL `T()` 更干净。
- **Rationale**：`force=true` 防管理员借关审计掩盖；`T()` SpEL 在 `StandardEvaluationContext` 可用，与既有 `#id`/`#request.x` 风格互补。

### D7. 密码强度规则与注册统一，新密不得同旧密
- **Decision**：改密/重置新密码均校验 `≥6 位含字母+数字`（与三个注册页前端 regex / 后端 `register` 一致）；改密额外校验新密码 != 旧密码（`passwordEncoder.matches(newPwd, oldHash)` 为真则报 `PASSWORD_SAME_AS_OLD`）。
- **Rationale**：全平台密码规则一致；防用户改回原密码。

### D8. 限频：按用户名 60s/次发送 + 每码 5 次校验上限
- **Decision**：`send-code` 查 `password_reset_otp` 同 `username` 最近一行 `created_at`，距今 < `send-cooldown-seconds`（默认 60）报 `PASSWORD_RESET_SEND_TOO_FREQUENT`（但仍返回 D4 统一前端消息，仅不发邮件不建码）；`reset` 每次校验码失败 `attempt_count+1`，达 `max-attempts`（默认 5）后该码置 `consumed_at`（视为作废），后续校验报 `PASSWORD_RESET_OTP_TOO_MANY_ATTEMPTS`。
- **Alternatives**：IP 限流--rejected（内网 NAT 共 IP 误杀 + 无需求）；全局令牌桶--rejected（过度工程）。
- **Rationale**：按用户名限频足以挡单账号暴破与邮件轰炸；6 位数字码 + 10 分钟有效期 + 5 次上限的组合暴破概率极低。

### D9. 过期/已消费码 @Scheduled 清理，复用既有调度
- **Decision**：`PasswordResetScheduler` 用 `@Scheduled` 每 `cleanup-interval-hours`（默认 6）删除 `expires_at < now - retain-days`（默认 7）的行（含已消费/已过期）。仿 `NasAllocationScheduler`。
- **Rationale**：表不过度膨胀；复用调度模式。

### D10. 配置经 application.yml nexcompute.password-reset.* ${ENV:default}（无 .env）
- **Decision**：`application.yml` 加 `nexcompute.password-reset.code-expire-minutes`(10)/`send-cooldown-seconds`(60)/`max-attempts`(5)/`cleanup-interval-hours`(6)/`cleanup-retain-days`(7)；新增 `PasswordResetProperties`（`@ConfigurationProperties(prefix="nexcompute.password-reset")`）。可选经 docker-compose `environment:` 覆盖（与 nas/newapi 一致，模板入库真实 gitignored）。
- **Rationale**：项目已移除 `.env`/`spring.config.import`，统一 docker-compose `environment:` 注入 + `application.yml` 默认值。

### D11. 禁用账号不可经忘记密码重置
- **Decision**：`send-code` 对 `status!=ACTIVE` 账号按「不存在」处理（不发邮件、不建码、返回 D4 统一消息）。
- **Alternatives**：允许禁用账号重置--rejected，已禁用（如离职）账号被重置是安全洞。
- **Rationale**：禁用是管理员主动处置，忘记密码不应绕过。

### D12. 工号/学号查用户用 username（登录标识），兼顾 studentId
- **Decision**：`send-code` / `reset` 按 `userRepository.findByUsername(studentId)` 查用户（注册时 `username=studentId`，二者同值；`username` 是登录唯一键 + unique 约束）。
- **Rationale**：与登录一致；`existsByUsername` 已用于注册查重，复用。

## Risks / Trade-offs

- **改密/重置后既有 JWT 仍有效至过期** -> 状态 JWT 无 blocklist（D 非目标）；本期不处理，文档记风险；未来可加 `password_changed_at` 版本号入 token 校验（需改 `JwtUtil` + 登录签发 + 每请求校验，属另一 change）。
- **工号/学号枚举** -> send-code 统一响应（D4）+ check-username 需有效邀请令牌（D5）双重缓解；无法完全消除时序侧信道（账号存在时多一次邮件发送，响应略慢），可接受。
- **验证码邮件发送失败/SMTP 未配** -> send-code 仍返回统一成功（D4），失败仅记 `email_log`；用户收不到码 -> 重试或线下找管理员（管理员重置既有）。可接受降级。
- **账号无邮箱** -> 无法自助重置，走管理员重置；文档与 UI 提示。
- **Flyway SQL 含 `${` 致启动失败** -> V34 全程不含 `${`（含注释/字符串），`ddl-auto=validate` 字段/类型与 JPA 实体精确对齐（记忆 [[flyway-no-placeholders-in-sql]]）。
- **@Audited SpEL `T()` 解析失败** -> 若 `targetIdExpr` 解析异常，`AuditAspect` catch 后 `targetId=null`（已降级），操作人仍捕获，审计不丢；属可接受降级。
- **限频按用户名** -> 攻击者可对不同用户名批量发邮件轰炸不同邮箱；内网场景可接受，未来可加 IP 维度。

## Migration Plan

- Flyway `V34__password_reset_otp.sql` 正向建表 + 索引 + 中文注释，无数据迁移（新表）。
- 回滚：`DROP TABLE password_reset_otp`（无外键被引，无依赖）。
- 部署：backend 镜像含 V34 + 新代码；前端构建含新视图/路由；`SecurityConfig.permitAll` 加新公开端点。无停机要求（表新建、端点新增）。
- 配置：默认值内置 `application.yml`，无需运维操作即可启用；可选 docker-compose `environment:` 覆盖阈值。

## Open Questions

- 是否需要在改密成功后「建议重新登录」提示（非强制）？倾向：提示但不强制跳转（既有 token 仍有效）。
- 验证码邮件是否在正文展示用户名（便于用户确认是哪个账号）？倾向：展示用户名（用户主动发起，非推送枚举）。
