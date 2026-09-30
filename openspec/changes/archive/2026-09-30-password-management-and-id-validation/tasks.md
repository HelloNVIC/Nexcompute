## 1. 数据库迁移（Flyway V34，D2）

- [x] 1.1 新建 `password_reset_otp` 表（`id BIGSERIAL PK`、`username VARCHAR(50) NOT NULL`、`code_hash VARCHAR(100) NOT NULL`、`expires_at TIMESTAMPTZ NOT NULL`、`consumed_at TIMESTAMPTZ NULL`、`attempt_count INT NOT NULL DEFAULT 0`、`created_at TIMESTAMPTZ NOT NULL DEFAULT now()`）+ 索引 `ix_pwd_reset_otp_username_created(username, created_at)` 与 `ix_pwd_reset_otp_expires(expires_at)`
- [x] 1.2 表/字段中文注释（`COMMENT ON`，仿 V32/V33 风格）
- [x] 1.3 全文检查 SQL 不含 `${`（含注释/字符串，记忆：placeholder 未配置致启动失败）；`ddl-auto=validate` 下字段/类型与 JPA 实体精确对齐
- [x] 1.4 迁移可正向执行且幂等；启动后 Hibernate validate 通过

## 2. 后端配置（D10）

- [x] 2.1 `application.yml` 加 `nexcompute.password-reset.*` 默认值（`code-expire-minutes:10` / `send-cooldown-seconds:60` / `max-attempts:5` / `cleanup-interval-hours:6` / `cleanup-retain-days:7`，`${ENV:默认}` 占位）
- [x] 2.2 新增 `config/PasswordResetProperties`（`@ConfigurationProperties(prefix="nexcompute.password-reset")`）读取上述键
- [x] 2.3 `docker-compose.example.yml` 与 `docker-compose.prod.example.yml` 的 backend `environment:` 块加 `PASSWORD_RESET_*` 占位（模板入库，真实 compose gitignored）；真实值留空即用默认
- [x] 2.4 验证无环境注入时仍可启动（回退 `application.yml` 默认值）

## 3. 领域与仓库（D2/D12）

- [x] 3.1 `domain/PasswordResetOtp.java`（JPA `@Entity`，字段对齐 1.1；`isExpired()`/`isConsumed()`/`isAttemptsExhausted(int max)` 便捷方法）
- [x] 3.2 `repository/PasswordResetOtpRepository`：`findFirstByUsernameOrderByCreatedAtDesc(username)`、`deleteByExpiresAtBefore(Instant)`、`existsByUsernameAndCreatedAtAfter(username, Instant)`（限频用）
- [x] 3.3 验证仓库查询/删除可走通

## 4. 用户自助改密（D6/D7）

- [x] 4.1 新增 DTO `ChangePasswordRequest`（`oldPassword`/`newPassword`，`@NotBlank` + `@Valid` 强度注解或服务端校验）
- [x] 4.2 `AuthService.changePassword(Long userId, String oldPassword, String newPassword)`：取用户 -> `passwordEncoder.matches(old, hash)` 校验旧密码（错抛 `OLD_PASSWORD_INCORRECT`）-> 新密码强度校验（`PASSWORD_TOO_WEAK`）-> 新旧同（`PASSWORD_SAME_AS_OLD`）-> `passwordEncoder.encode(new)` 落库
- [x] 4.3 `AuthController` 加 `POST /auth/change-password`（已认证，`SecurityUtils.getCurrentUserId()`），标 `@Audited(action="USER_CHANGE_PASSWORD", targetType="USER", targetIdExpr="T(com.nexcompute.management.security.SecurityUtils).getCurrentUserId()", force=true)`
- [x] 4.4 `ErrorCode` 新增 `OLD_PASSWORD_INCORRECT`(1013)、`PASSWORD_SAME_AS_OLD`(1014)（接 `PASSWORD_TOO_WEAK` 1004 既有）
- [x] 4.5 验证旧密码错误/强度不足/新旧相同/成功各路径；审计日志恒记（关审计开关仍记，`force=true`）

## 5. 忘记密码-发送验证码（D3/D4/D8/D11）

- [x] 5.1 `EmailService` 加 `sendPasswordResetCode(String toEmail, String username, String fullName, String code, int expireMinutes)`：仿 `sendNasMail`，内联 HTML + Logo CID + 品牌/落款读 `system_config`，`trigger_key="PASSWORD_RESET_CODE"` 写 `email_log`，`@Async` 不阻断；正文含用户名与验证码
- [x] 5.2 `AuthService.sendPasswordResetCode(String username)`：`findByUsername` -> 账号不存在/`status!=ACTIVE`/无邮箱 -> 直接返回统一消息（不发邮件不建码，D4/D11）-> 限频检查（`existsByUsernameAndCreatedAtAfter(now - cooldown)` 命中抛 `PASSWORD_RESET_SEND_TOO_FREQUENT`，但 service 仍返回统一消息，仅不建码不发）-> 生成 6 位数字码 -> `passwordEncoder.encode(code)` 哈希 -> 写 `password_reset_otp`（`expires_at=now+expireMinutes`）-> 异步 `emailService.sendPasswordResetCode(...)`
- [x] 5.3 `AuthController` 加 `POST /auth/forgot-password/send-code`（公开，body `{ username }`），返回 `{ message }` 统一成功消息（D4）
- [x] 5.4 `ErrorCode` 新增 `PASSWORD_RESET_SEND_TOO_FREQUENT`(1015)
- [x] 5.5 验证账号存在+启用+有邮箱时建码+发邮件；账号不存在/禁用/无邮箱返回统一消息且不建码；60s 内重复不建码

## 6. 忘记密码-重置（D2/D7/D8）

- [x] 6.1 新增 DTO `ForgotPasswordResetRequest`（`username`/`code`/`newPassword`，`@NotBlank` + 强度校验）
- [x] 6.2 `AuthService.resetPassword(String username, String code, String newPassword)`：取最近未消费码 `findFirstByUsernameOrderByCreatedAtDesc` -> 无码/已消费抛 `PASSWORD_RESET_OTP_NOT_FOUND` -> `attempt_count>=max` 抛 `PASSWORD_RESET_OTP_TOO_MANY_ATTEMPTS` -> `expires_at<now` 抛 `PASSWORD_RESET_OTP_EXPIRED` -> `passwordEncoder.matches(code, code_hash)` 失败则 `attempt_count+1` 落库后抛 `PASSWORD_RESET_OTP_INVALID`（命中上限时改抛 TOO_MANY_ATTEMPTS 并置 `consumed_at`）-> 成功则新密码强度校验 + `encode` 落库 `app_user` + 置 `consumed_at`
- [x] 6.3 `AuthController` 加 `POST /auth/forgot-password/reset`（公开）
- [x] 6.4 `ErrorCode` 新增 `PASSWORD_RESET_OTP_NOT_FOUND`(1016)/`PASSWORD_RESET_OTP_EXPIRED`(1017)/`PASSWORD_RESET_OTP_CONSUMED`(1018)/`PASSWORD_RESET_OTP_TOO_MANY_ATTEMPTS`(1019)/`PASSWORD_RESET_OTP_INVALID`(1020)
- [x] 6.5 `SecurityConfig.permitAll` 加 `/auth/forgot-password/**`
- [x] 6.6 验证重置成功/码错/码过期/码作废后重试/达上限作废；重置后可用新密码登录

## 7. 注册页工号/学号可用性校验（D5）

- [x] 7.1 `AuthService.checkUsernameAvailable(String token, String linkType, String studentId)`：先调 `validateRegistrationLink(token, linkType)`，`valid=false` 返回 `{ available:false, reason:"邀请链接无效" }`（不查重，D5）-> `valid=true` 则 `existsByUsername(studentId)` 返回 `{ available }`
- [x] 7.2 新增 DTO `UsernameAvailabilityResult`（`available:boolean`、`reason:String`）
- [x] 7.3 `AuthController` 加 `GET /auth/register/check-username`（`@RequestParam token/linkType/studentId`，公开）
- [x] 7.4 `SecurityConfig.permitAll` 加 `/auth/register/check-username`
- [x] 7.5 验证令牌有效时可用/已占用返回正确；令牌无效时仅返回「邀请链接无效」不泄露存在性

## 8. 过期/已消费码清理调度（D9）

- [x] 8.1 新增 `service/PasswordResetScheduler`：`@Scheduled` 每 `cleanup-interval-hours`（默认 6）删 `expires_at < now - retain-days`（默认 7）的行（`deleteByExpiresAtBefore`）
- [x] 8.2 验证扫描不阻塞业务、幂等、日志可查

## 9. 前端-用户信息改密卡片（D7）

- [x] 9.1 `views/ProfileView.vue` 新增「修改密码」卡片：旧密码 / 新密码 / 确认新密码三栏 + AntD 表单校验（新密码 ≥6 含字母数字、确认一致）
- [x] 9.2 提交调 `authApi.changePassword`，成功 Toast 提示 + 清空表单；失败由 request 拦截器按后端 code 提示（旧密码错误/强度不足/新旧相同）
- [x] 9.3 验证三角色（管理员/导师/学生）均可见与可用

## 10. 前端-忘记密码页与入口（D4）

- [x] 10.1 新增 `views/auth/ForgotPasswordView.vue`：两步表单（step1 工号/学号 + 发送验证码按钮带 60s 倒计时；step2 验证码 + 新密码 + 确认新密码）+ 成功后跳 `/login`；统一显示「若账号存在，验证码已发送至其绑定邮箱」
- [x] 10.2 `views/auth/LoginView.vue` 加「忘记密码?」链接跳 `/forgot-password`
- [x] 10.3 `router/index.ts` 加 `/forgot-password`（`meta.public:true`）
- [x] 10.4 验证发送限频倒计时、重置成功跳登录、各错误码提示

## 11. 前端-注册页工号/学号 onBlur 校验（D5）

- [x] 11.1 `RegisterView.vue` 工号/学号输入框 onBlur 调 `authApi.checkUsername(token, 'STUDENT', studentId)`，可用绿勾 / 已占用红字
- [x] 11.2 `MentorRegisterView.vue` 同上（`linkType='MENTOR'`）
- [x] 11.3 `AdminRegisterView.vue` 同上（`linkType='ADMIN'`）
- [x] 11.4 验证三页可用/已占用实时提示；邀请链接无效时不显示存在性判断（仅页面顶部失效提示）

## 12. 前端 API 封装

- [x] 12.1 `api/auth.ts` 加 `changePassword(oldPassword, newPassword)`、`sendResetCode(username)`、`resetPassword(username, code, newPassword)`、`checkUsername(token, linkType, studentId)`
- [x] 12.2 验证封装与 `http` util 拼参/正文一致（GET 走 query，POST 走 body）

## 13. 端到端验证

- [x] 13.1 改密：旧密码错误/强度不足/新旧相同/成功；审计恒记（关审计开关仍记）
- [x] 13.2 忘记密码：存在+启用+有邮箱账号 -> 收码 -> 重置 -> 新密码登录成功
- [x] 13.3 忘记密码：不存在/禁用/无邮箱账号 -> 统一响应、不收码、不建码
- [x] 13.4 忘记密码：60s 内重复发送不建码不重复发邮件；码错 5 次作废须重发
- [x] 13.5 忘记密码：码过期后重置被拒
- [x] 13.6 注册页：三页工号/学号可用/已占用实时提示；邀请链接失效时不泄露存在性
- [x] 13.7 非登录访问 `/auth/change-password` 被拒；公开端点（send-code/reset/check-username）免鉴权
- [x] 13.8 过期码清理调度跑通，表不膨胀
- [x] 13.9 `application.yml` 默认值生效；docker-compose `environment:` 覆盖生效（模板与真实同源）
- [x] 13.10 `openspec validate password-management-and-id-validation --strict` 通过
