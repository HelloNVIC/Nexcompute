## Why

NewAPI（LLM 网关，`http://10.13.66.18:3001`，v1.0.0-rc.22）的用户账号当前由管理员在 NewUI 手动逐个创建，存在与 TrueNAS 用户开通同样的三个问题：无审批留痕、无可控邀请（无法限定名额/有效期）、无业务侧元信息（姓名/手机号/邮箱）。本仓已有的 `nas-allocation` 模块（`openspec/specs/nas-allocation/spec.md`）已用 SpringBoot+Vue 解决了等价问题：邀请门控注册 + 管理员审批 + 上游 REST 自动开通。需将其模式 1:1 复刻到 NewAPI 用户账号，成为管理员后台「Token分配」模块：上游从 TrueNAS 换成 NewAPI（建用户走 `POST /api/user/`），角色组 40/41/42 换成 `group` 分组，复用平台 ADMIN 鉴权与 `nexcompute` 库，密钥经 `docker-compose` `environment:` 注入。

## What Changes

**邀请门控注册（NewAPI 用户管理）**
- 新建 `newapi_invitation` 表（token / label / max_uses / used_count / expires_at / created_by->app_user.id / revoked_at），管理员创建/列表/详情/撤销。token 用 `SecureRandom` 生成不可猜测串，返回完整注册链接 `{portal}/newapi-register?token={token}`。
- 名额原子消耗：`@Modifying @Query` 条件 UPDATE（`used_count < max_uses AND revoked_at IS NULL AND expires_at > now`），并发不超发；过期/撤销/名额耗尽各自精确报错。

**注册申请（公开）**
- 新增公开 Vue 路由 `/newapi-register?token=` + 公开 API `POST /api/newapi-allocation/register`，服务端校验 token 后接受表单（username / display_name / 邮箱 / 手机号 / 密码 ≥8 位含字母+数字）。
- 提交：原子消耗名额 -> AES-GCM 加密密码（复用既有 `PASSWORD_ENC_KEY`，密文格式 `b64url(nonce12+ct+tag)`）-> 写 `PENDING` 行（**不调 NewAPI**）-> 返回「待审批」。
- 用户名占用：本地 `PENDING/APPROVED/FAILED` 占用不可再注册（仅 `REJECTED` 释放）；同时调 NewAPI `GET /api/user/search?keyword=` 查重，NewAPI 已存在则报 `NEWAPI_TAKEN`（不可达不阻断）。
- pending 过期（默认 7 天）由 `@Scheduled` 扫描，擦除密码并置 `REJECTED`。

**审批（管理员）**
- 新建 `newapi_registration` 表，状态机 `PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND`。
- 批准并开通：解密密码 -> NewAPI 幂等查重（`GET /api/user/search?keyword=`）-> 不存在则 `POST /api/user/` 建用户 -> 建后 `search` 回查拿 `newapi_user_id` 回填（建用户响应无 id）-> 擦除密码 -> `APPROVED`；失败置 `FAILED` 保留密码可重试。批准时管理员选 NewAPI **`group` 分组**（决定可访问渠道/模型，对应 NAS 的 40/41/42 角色组）。
- 拒绝：擦除密码 -> `REJECTED`（不退名额，用户名释放）。删除记录（不删 NewAPI 用户）。改分组（已开通）：`PUT /api/user/` 改 `group`（body 须含 id+username；不改 quota）。
- 用户不存在（NewAPI 上用户已删）：详情自动翻转 `NOT_FOUND`，可「重申上游」用新密码重新建用户。点「详情」/「刷新状态」批量复核所有已开通用户在 NewAPI 是否还在（`GET /api/user/{id}` 404 翻 `NOT_FOUND`）。

**NewAPI 集成**
- 新增 `NewApiClient`（Spring `RestClient`，同步），`Authorization: Bearer <系统访问令牌>` + `New-Api-User: <uid>` 头。方法：`statusPing` / `userCreate` / `userSearch` / `userGetInstance` / `userUpdate` / `userDelete`。
- NewAPI 为 http，**无需自签 TLS 关校验**（比 `TrueNasClient` 简单）。
- **额度**：NewAPI 用户无 `unlimited_quota` 字段，建用户时 `quota` 被忽略、`PUT /api/user/` 也不改 quota；额度走 NewAPI 全局 `QuotaForNewUser`（建用户即带默认额度，非合规门控）。设大值 = "实际无限"。此为 NewAPI 侧一次性运维设置（`PUT /api/option/ {"key":"QuotaForNewUser","value":"<大值>"}`），**不写进功能代码**；配套需关闭 NewAPI 公开自注册（`register_enabled`/`password_register_enabled`）防白嫖。
- 启动 `GET /api/status` ping 记日志告警不阻断；**严禁调用 `GET /api/user/token`**（会轮换系统访问令牌，旧令牌立即失效）。

**鉴权与配置**
- 复用平台 `app_user`（role=ADMIN）+ 平台 JWT。`created_by`/`reviewed_by` FK->`app_user.id`。管理员端走 JWT + `requireAdmin()` + `@Audited`；公开注册接口加进 `SecurityConfig.permitAll`。
- `docker-compose.example.yml` 与 `docker-compose.prod.example.yml` 的 backend `environment:` 块加 `NEWAPI_BASE_URL`/`NEWAPI_ACCESS_TOKEN`/`NEWAPI_API_USER`/`NEWAPI_*` 键（模板入库，真实 compose gitignored）；`application.yml` 加 `nexcompute.newapi.*` 的 `${ENV:default}` 占位；`NewApiProperties`（`nexcompute.newapi.*`）读取。

## Capabilities

### New Capabilities
- `newapi-user-allocation`: 管理员后台「Token分配」模块。邀请令牌门控的 NewAPI 用户注册 + 管理员审批开通全流程，1:1 复刻 `nas-allocation` 模式并入 Nexcompute SpringBoot+Vue 栈与 `nexcompute` 库，复用平台 ADMIN 鉴权。包含：邀请管理（创建/列表/撤销、原子名额消耗）、公开注册（AES-GCM 密码暂存、用户名占用、pending 过期扫描）、审批（批准开通/改分组/重申上游/拒绝/删除/批量刷新状态、NewAPI REST 集成、5 态机）、NewAPI REST 客户端（Bearer + New-Api-User，无自签 TLS）、统一 docker-compose `environment:` 配置注入、启动健康检查。额度经 NewAPI 全局 `QuotaForNewUser` 默认值（运维前置，非功能代码）。

## Impact

**管理端后端（SpringBoot）**
- 新增 `domain/NewApiInvitation.java`、`domain/NewApiRegistration.java`；`repository/NewApiInvitationRepository`（含 `@Modifying @Query` 原子名额 UPDATE）、`NewApiRegistrationRepository`。
- 新增 `service/NewApiInvitationService`、`NewApiRegistrationService`、`NewApiClient`（`RestClient`）、`NewApiAllocationScheduler`（`@Scheduled`）；**复用** `NasPasswordEncryptor`（AES-GCM，同 `PASSWORD_ENC_KEY`，密文格式一致）。
- 新增 `controller/NewApiInvitationController`（`/admin/newapi-invitations`）、`NewApiRegistrationController`（`/admin/newapi-registrations`）、`NewApiPublicRegisterController`（`/newapi-allocation/register`，公开）。
- 新增 `config/NewApiProperties`（`nexcompute.newapi.*`）；`application.yml` 加 `nexcompute.newapi.*` 默认值；`SecurityConfig` permitAll 加 `/newapi-allocation/register/**`。
- 全部管理员端方法加 `@Audited` + `requireAdmin()`；新增 `ErrorCode` 项（名额耗尽/令牌失效/状态非法/NewAPI 不可达等）。

**管理端前端（Vue 3 / AntD）**
- 新增 `views/admin/NewApiUserAllocationView.vue`（`a-tabs`：邀请管理 / 审批队列 / 审批历史 / 申请记录，含详情/批准/拒绝/改分组/重申上游/删除弹窗 + Toast + 状态徽章）。
- 新增公开路由 `views/auth/NewApiRegisterView.vue`（token 校验态 + 表单 + 「待审批」成功页）。
- 新增 `api/newApiUserAllocation.ts`；`router/index.ts` 加 `/admin/newapi-user-allocation`（ADMIN）+ `/newapi-register`（public）；`layouts/BasicLayout.vue` 菜单加「Token分配」（ADMIN）。

**基础设施**
- Flyway `V33__newapi_user_allocation.sql`：新建 `newapi_invitation`、`newapi_registration` 两表 + 索引 + 中文注释。SQL 全程不含 `${`（含注释/字符串，记忆：placeholder 未配置致启动失败）。

**运维前置（非功能代码，NewAPI 侧一次性）**
- `PUT /api/option/ {"key":"QuotaForNewUser","value":"<大值>"}` 设新用户默认额度（"实际无限"）；关闭 NewUI 公开自注册（`register_enabled`/`password_register_enabled`）防白嫖。功能不依赖 per-user 额度授予。
