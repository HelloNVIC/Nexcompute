## Why

平台登录体系当前缺三块自助能力：登录后用户无法在「用户信息」自助改密（只能找管理员重置）；忘记密码时无自助找回通道（用户被锁死只能线下求助管理员）；学生/导师/管理员三套邀请注册页输入工号/学号后无即时可用性校验，提交才报「已被注册」、体验差且浪费已通过校验的邀请名额。需补齐这三块以闭环账号自助管理与注册体验，与既有邀请门控注册、管理员重置密码、邮件通知体系对齐。

## What Changes

**用户信息自助改密（登录态）**
- 新增 `POST /auth/change-password`（已认证）：提交旧密码 + 新密码，服务端校验旧密码匹配 + 新密码强度（≥6 位含字母+数字，与注册规则一致），BCrypt 重新哈希落库。
- `ProfileView.vue` 新增「修改密码」卡片：旧密码 / 新密码 / 确认新密码三栏 + 前端强度与一致性校验 + 成功提示。
- 控制器方法加 `@Audited(action="USER_CHANGE_PASSWORD", targetType="USER", force=true)`（安全敏感恒审计）。

**忘记密码（公开，邮箱验证码）**
- 新增公开 Vue 路由 `/forgot-password` + 登录页「忘记密码?」入口；两步：输入工号/学号 -> 邮箱收验证码 -> 输入验证码 + 新密码重置。
- 新增 `password_reset_otp` 表（Flyway `V34`）：`username` / `code_hash`（BCrypt 哈希不存明文）/ `expires_at`（默认 10 分钟）/ `consumed_at` / `attempt_count` / `created_at`。
- 公开端点：`POST /auth/forgot-password/send-code`（凭工号/学号查用户 -> 生成 6 位数字码 -> BCrypt 哈希入库 -> 异步发邮件至用户绑定邮箱；按用户名限频 60s/次、每码最多 5 次校验）、`POST /auth/forgot-password/reset`（校验码未过期未消费未锁 -> 校验新密码强度 -> 重置 -> 标记码已消费）。
- 邮件经既有 `EmailService` 的 `sendNasMail` 风格通道（`trigger_key=PASSWORD_RESET_CODE`，写 `email_log`），不新增 `EmailTrigger` 枚举。
- 安全：无论账号是否存在均返回统一「若账号存在，验证码已发送至绑定邮箱」防枚举；账号不存在时不发邮件；过期/已用尽码由 `@Scheduled` 清理。

**邀请注册页工号/学号可用性校验（公开，邀请门控）**
- 新增公开端点 `GET /auth/register/check-username?token=&linkType=&studentId=`：先复用 `validateRegistrationLink` 校验邀请链接有效（防止开放枚举），再 `userRepository.existsByUsername(studentId)` 返回 `{ available }`。
- 学生 / 导师 / 管理员三个注册页（`RegisterView.vue` / `MentorRegisterView.vue` / `AdminRegisterView.vue`）工号/学号输入框 onBlur 调用，实时绿勾/红字提示。

## Capabilities

### New Capabilities
<!-- 无：三块能力均归属既有 access-control 认证域，作为其需求扩展，不新设独立 capability。 -->

### Modified Capabilities
- `access-control`: 新增三条 spec 级需求——登录态自助改密、忘记密码邮箱验证码重置（公开）、邀请注册页工号/学号可用性即时校验（公开，邀请门控）。扩展现有「登录会话失效与失败提示」「用户编辑与课题组归属管理（含管理员重置密码）」的认证体系，补齐用户侧密码自助与注册前校验。

## Impact

**管理端后端（SpringBoot）**
- `AuthController` 新增 4 端点：`POST /auth/change-password`（已认证）、`POST /auth/forgot-password/send-code`（公开）、`POST /auth/forgot-password/reset`（公开）、`GET /auth/register/check-username`（公开）。
- `AuthService` 新增 `changePassword(userId, oldPwd, newPwd)` / `sendPasswordResetCode(username)` / `resetPassword(username, code, newPwd)` / `checkUsernameAvailable(token, linkType, studentId)`；复用 `validateRegistrationLink`、`passwordEncoder`、`userRepository`、`emailService`。
- 新增 `domain/PasswordResetOtp.java` + `repository/PasswordResetOtpRepository`（按用户名查最近未消费码、按 expires_at 清理）。
- 新增 `service/PasswordResetScheduler`（`@Scheduled` 清理过期/已消费码，复用既有调度模式）。
- `SecurityConfig.permitAll` 加 `/auth/forgot-password/**`、`/auth/register/check-username`。
- `ErrorCode` 新增：`PASSWORD_RESET_OTP_NOT_FOUND`/`PASSWORD_RESET_OTP_EXPIRED`/`PASSWORD_RESET_OTP_CONSUMED`/`PASSWORD_RESET_OTP_TOO_MANY_ATTEMPTS`/`PASSWORD_RESET_SEND_TOO_FREQUENT`/`OLD_PASSWORD_INCORRECT`/`REGISTRATION_LINK_INVALID`（复用既有）等。
- Flyway `V34__password_reset_otp.sql`：新建 `password_reset_otp` 表 + 索引 + 中文注释（SQL 不含 `${`，记忆：placeholder 未配置致启动失败）。

**管理端前端（Vue 3 / AntD）**
- `views/ProfileView.vue` 加「修改密码」卡片（旧/新/确认 + 校验 + Toast）。
- 新增 `views/auth/ForgotPasswordView.vue`（两步表单 + 验证码倒计时 + 成功后跳登录）。
- `views/auth/LoginView.vue` 加「忘记密码?」链接。
- `views/auth/RegisterView.vue` / `MentorRegisterView.vue` / `AdminRegisterView.vue` 工号/学号 onBlur 调 `checkUsername` + 状态徽标。
- `api/auth.ts` 加 `changePassword` / `sendResetCode` / `resetPassword` / `checkUsername` 封装。
- `router/index.ts` 加 `/forgot-password`（public）。

**基础设施**
- Flyway `V34` 建表；`ddl-auto=validate` 下字段/类型与 JPA 实体精确对齐；无新外部依赖（BCrypt/JavaMail/Scheduled 均已用）。

**运维**
- 忘记密码邮件依赖既有 SMTP 配置（`system_config` `email.smtp.*` / `NexcomputeProperties`），无新配置项；OTP 有效期/限频阈值/清理周期经 `application.yml` `nexcompute.password-reset.*` `${ENV:default}` 占位（默认值内置，可选经 docker-compose `environment:` 覆盖）。
