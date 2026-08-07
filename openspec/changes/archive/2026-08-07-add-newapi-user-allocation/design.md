## Context

NewAPI（`http://10.13.66.18:3001`，v1.0.0-rc.22）是已上线的 LLM 网关，用户账号当前由管理员在 NewUI 手动创建。需 1:1 复刻本仓 `nas-allocation` 模块的「邀请门控注册 + 管理员审批 + 上游 REST 自动开通」模式，把上游从 TrueNAS 换成 NewAPI。关键现状（经实测 + 两仓代码确认）：

**NewAPI 用户管理 API（实测，管理员权限 + `New-Api-User` 头）**
- `POST /api/user/`（Admin）建用户，body 接受 `username`/`password`/`display_name`/`group`/`role`；**`quota` 被忽略**；响应仅 `{success:true}`，**无 id**，须 `GET /api/user/search?keyword=` 回查拿 id 回填。
- `PUT /api/user/`（Admin）body 须含 `id`+`username`（缺 username 报 `Invalid parameters`）；能改 `display_name`/`group`/`role`/`status`/`password`，**但不能改 quota**（success 却不变）。
- `GET /api/user/{id}`（Admin）详情，404 = 用户已删（用于 NOT_FOUND 翻转）；`GET /api/user/search?keyword=` 用户名查重；`DELETE /api/user/{id}` 删除；`POST /api/user/manage` 状态管理（disable/enable）。
- **⚠ `GET /api/user/token` 不是只读**：它会重新生成当前用户的系统访问令牌，旧令牌立即失效（实测原令牌被轮换）。客户端严禁调用。
- **用户无 `unlimited_quota`**（只有 token 对象有该字段）。建用户传 `unlimited_quota:true`/`quota:-1`/大额/`role:10` 全部创建为 `quota=0`。不存在"无限额度用户"。
- **额度授予**：`PUT /api/option/ {"key":"QuotaForNewUser","value":"<数字字符串>"}` 设全局新用户默认额度；之后 `POST /api/user/` 建的用户**自动带该额度**，**非合规门控**（`payment_setting.compliance_confirmed=false` 时仍生效）。设大值 = "实际无限"。
- **合规闸门**：`payment_setting.compliance_confirmed=false`（默认）时禁用 `POST /api/user/topup`、兑换码、订阅、邀请奖励（报 "Payment, redemption... disabled"）。per-user 充值走 `POST /api/user/topup/complete`（body 未摸清，且需先开合规）--用 `QuotaForNewUser` 方案则完全不需要。
- NewAPI 为 http，无需自签 TLS 处理。
- user 对象字段：`id`/`username`/`password`(只写)/`display_name`/`role`(100/10/1)/`status`(1启用/2禁用)/`email`/`group`/`quota`/`used_quota`/`created_at`/`last_login_at` 等（**无 phone 字段**，手机号仅本地存）。
- `QuotaForNewUser` 是全局的，也作用于 NewAPI 公开自注册（`password_register_enabled:true`）；设大值时必须关 NewUI 自注册防白嫖。

**目标端（Nexcompute，已确认）**
- SpringBoot 3.5 + Java 21 + Spring Data JPA（`ddl-auto=validate`，schema 仅经 Flyway，当前 V32）+ PostgreSQL + jjwt + Spring Security + Spring Mail + Lombok。
- 前端 Vue 3 + Vite + TS + Ant Design Vue；`router/index.ts` 已有公开路由模式 + `meta.roles:['ADMIN']` 管理路由模式。
- 平台已有 `@Audited` AOP 切面 + `SecurityUtils.getCurrentRole()/getCurrentUserId()`；`ApiResponse`/`BusinessException`/`ErrorCode`/`GlobalExceptionHandler`。
- **`nas-allocation` 模块已存在**（`openspec/specs/nas-allocation/spec.md`）：`NasInvitation`/`NasRegistration` 实体、`NasInvitationRepository.redeem`（`@Modifying @Query` 条件 UPDATE）、`NasRegistrationService`（5 态机 `PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND`）、`TrueNasClient`（`RestClient`+Bearer+自签 TLS）、`NasPasswordEncryptor`（AES-GCM，`PASSWORD_ENC_KEY`，密文 `b64url(nonce12+ct+tag)`）、`NasAllocationScheduler`（`@Scheduled` 过期扫描）、`NasProperties`（`nexcompute.nas.*`）、前端 `NasAllocationView.vue`（4 tab）。本 change 直接复刻其结构与命名。
- 配置经 `docker-compose.yml`/`docker-compose.prod.yml` 的 `environment:` 内联注入（真实文件 gitignored，模板 `docker-compose.example.yml`/`docker-compose.prod.example.yml` 入库）；`application.yml` 全程 `${ENV:default}` 占位，无注入回退默认值。**`spring.config.import` 已移除、`.env` 不再被 Spring 读取**（`.env.example` 已删，`.env` 仅本地 bootRun 可选手工 source）。`@Scheduled` 已用（`HeartbeatTimeoutScheduler`/`NasAllocationScheduler`）。
- 记忆：Flyway SQL 含 `${...}`（含注释/字符串）会致启动失败（placeholder 未配置）；docker-compose `environment:` 值含特殊字符时用引号包裹（YAML 规则，非 Spring 剥离）。

## Goals / Non-Goals

**Goals:**
- 管理员后台「Token分配」入口：邀请管理 + 审批 + 已开通用户管理，全流程留痕。
- 邀请门控的公开注册：名额原子消耗、AES-GCM 密码暂存、pending 过期自动清理。
- 审批开通经 NewAPI REST 自动建用户，支持改分组/重申上游/批量刷新状态。
- 全栈 SpringBoot + Vue，并入 `nexcompute` 库（单 DataSource / 单 Flyway），复用平台 ADMIN 鉴权。
- NewAPI 连接、AES 密钥等配置经 `docker-compose` `environment:` 注入（模板 `.example.yml` 入库，真实 compose gitignored）。
- 1:1 保留 `nas-allocation` 的 5 态机 / 上游 REST 调用语义 / 名额原子性。
- 额度免 per-user 逻辑：经 NewAPI 全局 `QuotaForNewUser`，建用户即带默认额度。

**Non-Goals:**
- 不做 per-user 额度授予（topup/兑换码，需合规闸门；改由 NewAPI 全局 `QuotaForNewUser` + 关自注册覆盖）。
- 不做用户端登录后门户（开通后直接用 NewUI）。
- 不做短信/邮件推送之外的通知（复用平台 `email-notification`，同 nas-allocation）。
- 不做 NewAPI 渠道/模型/分组配置（仅建用户账号与 `group` 分组）。
- 不做 SSO / 2FA / WebSocket。
- 不写 NewAPI 自注册门户（NewUI 自注册应关闭；账号只经 Nexcompute 邀请门控分配）。
- 不接入 NewAPI 兑换码/支付/订阅。

## Decisions

### D1. 并入 nexcompute 库，单 DataSource（镜像 nas D1）
- **Decision**：新建 `newapi_invitation`/`newapi_registration` 两表，经 Flyway `V33` 建在 `nexcompute` 库；复用既有 DataSource / Flyway / JPA `validate`。
- **Rationale**：与 nas-allocation 一致，单库最干净，事务边界简单。

### D2. 独立 newapi_ 前缀表，FK->app_user.id（镜像 nas D2）
- **Decision**：`newapi_invitation` / `newapi_registration` 独立表，`created_by`/`reviewed_by` FK->`app_user.id`。
- **Alternatives**：扩 `RegistrationLink` 加 `linkType=NEWAPI` --rejected，NewAPI 邀请产物是外部 NewAPI 用户（非平台 `User`），与平台注册链接语义不同；耦合会令 redemption 按 linkType 长期分叉。
- **Rationale**：关注点分离，NewAPI 5 态机与 NewAPI 集成自成一体不污染平台注册链接。

### D3. 全新开始，不写数据迁移脚本（镜像 nas D3）
- **Decision**：`V33` 仅 CREATE TABLE + 索引 + 注释，不迁历史数据；AES key 复用既有 `PASSWORD_ENC_KEY`（密文格式与 nas 一致）。

### D4. 配置经 docker-compose environment: 注入（spring.config.import 已移除，镜像 nas D4 实际机制）
- **Decision**：配置经 `docker-compose.yml`/`docker-compose.prod.yml` 的 `environment:` 内联注入。新增 `NEWAPI_BASE_URL`/`NEWAPI_ACCESS_TOKEN`/`NEWAPI_API_USER`/`NEWAPI_TIMEOUT_SECONDS`/`NEWAPI_RETRIES` 到 `docker-compose.example.yml` 与 `docker-compose.prod.example.yml` 的 backend `environment:` 块（模板入库，真实 compose gitignored）；`application.yml` 加 `nexcompute.newapi.*` 的 `${ENV:default}` 占位。新增 `NewApiProperties`（`@ConfigurationProperties(prefix="nexcompute.newapi")`）读取。bootRun 经 shell 环境变量或回退默认值。
- **Alternatives**：恢复 `.env` + `spring.config.import`--rejected，项目已主动移除该机制（`.env.example` 已删、`application.yml` 注释"不再读取根 .env"），统一走 docker-compose `environment:`。
- **Rationale**：与 nas-allocation 实际部署机制一致；单一注入源避免 `.env` 与 compose 两处维护。

### D5. 复用平台 ADMIN 鉴权（镜像 nas D5）
- **Decision**：管理员端接口走平台 JWT + `requireAdmin()`；公开注册接口加进 `SecurityConfig.permitAll`（`/newapi-allocation/register/**`）。`created_by`/`reviewed_by` FK->`app_user.id`。
- **Rationale**：平台已有 ADMIN 体系，无需重复。

### D6. NewApiClient 用 Spring RestClient（同步），无自签 TLS（比 nas D6 简单）
- **Decision**：`RestClient`（Spring 6.1 同步 builder），`Authorization: Bearer ${accessToken}` + `New-Api-User: ${apiUser}` 默认头。方法 1:1 对照 `TrueNasClient`：`statusPing`（`GET /api/status`）/ `userCreate`（`POST /api/user/`）/ `userSearch`（`GET /api/user/search`）/ `userGetInstance`（`GET /api/user/{id}`）/ `userUpdate`（`PUT /api/user/`）/ `userDelete`（`DELETE /api/user/{id}`）。
- **Alternatives**：(a) WebClient（reactive）--rejected，整栈同步；(b) Java 11 `HttpClient`--可用但不如 RestClient Spring 惯用。
- **Rationale**：NewAPI 为 http，**无需自签 TLS 关校验**（nas-allocation D6 的 `TRUST_ALL` SSLContext 在此省略），比 `TrueNasClient` 更简。同步栈匹配 JPA 同步。

### D7. AES-GCM 复用 NasPasswordEncryptor / PASSWORD_ENC_KEY（密文格式一致，镜像 nas D7）
- **Decision**：复用既有 `NasPasswordEncryptor`（AES/GCM/NoPadding，12B nonce，密文 `b64url(nonce+ct+tag)`，密钥 `PASSWORD_ENC_KEY`）。NewAPI 用户建账号同样需用户自选密码暂存->批准时解密->`POST /api/user/` 传 password，与 nas 完全同构。
- **Alternatives**：新建 `NewApiPasswordEncryptor` 同格式同 key--可行但重复；或抽共享 `PasswordEncryptor` 重命名--更干净但改动既有类名。**推荐复用 `NasPasswordEncryptor` 不重命名**，避免触碰已上线 nas 代码；若担忧命名误导，可在 javadoc 注明"NAS/NewAPI 共用"。
- **Rationale**：密文格式与密钥统一，避免密钥散落；1:1 对照 nas 便于核对。

### D8. 名额原子消耗用 @Modifying @Query 条件 UPDATE（镜像 nas D8）
- **Decision**：`NewApiInvitationRepository.redeem(token)` 用 `@Modifying @Query("UPDATE NewApiInvitation i SET i.usedCount = i.usedCount + 1 WHERE i.token = :token AND i.usedCount < i.maxUses AND i.revokedAt IS NULL AND i.expiresAt > now")`，返回受影响行数；0 即名额耗尽/过期/撤销。注册事务内先 redeem 再写 registration，同 commit。
- **Rationale**：原 nas Python 版即单条条件 UPDATE 保并发安全，JPA 等价做法是 `@Modifying` 条件 UPDATE。

### D9. pending 过期扫描用 @Scheduled，复用既有调度（镜像 nas D9）
- **Decision**：`NewApiAllocationScheduler` 用 `@Scheduled`，扫描 `status=PENDING AND submitted_at < now - expireDays` 的行，擦 `password_enc` 并置 `REJECTED`。
- **Rationale**：复用平台既有调度模式。

### D10. 启动 ping 用 GET /api/status，禁止 /api/user/token
- **Decision**：`@EventListener(ApplicationReadyEvent)` 调 `GET /api/status`（公开，返回系统信息）记 WARN 不阻断。**严禁调用 `GET /api/user/token`**（会轮换系统访问令牌，旧令牌立即失效）。`userCreate` 在 `approve` 时若令牌权限不足才暴露为 `FAILED`。
- **Alternatives**：启动校验失败即拒绝启动--rejected，NewAPI 不可达不应令整个管理端起不来。
- **Rationale**：`GET /api/status` 只读安全；`GET /api/user/token` 有副作用（轮换），客户端方法集显式排除之。

### D11. 额度走 NewAPI 全局 QuotaForNewUser，不实现 per-user 额度逻辑（NewAPI 特有）
- **Decision**：NewAPI 用户无 `unlimited_quota`，建用户时 `quota` 被忽略、`PUT` 也不改 quota。额度由 NewAPI 侧一次性 `PUT /api/option/ {"key":"QuotaForNewUser","value":"<大值>"}` 设全局默认，建用户即带。**功能代码不涉及额度授予**。设大值（如 `100000000`+）= "实际无限"。
- **Alternatives**：per-user topup（`POST /api/user/topup/complete`）--rejected，需先开 `payment_setting.compliance_confirmed` 合规闸门 + body 未摸清；且 QuotaForNewUser 方案免 per-user 逻辑更简。
- **Rationale**：实测 `QuotaForNewUser` 对 admin 建用户生效且非合规门控；满足"建用户即带额度"需求。

### D12. 运维前置：设 QuotaForNewUser + 关 NewAPI 自注册（NewAPI 特有，非功能代码）
- **Decision**：NewAPI 侧一次性运维：(1) `PUT /api/option/` 设 `QuotaForNewUser` 大值；(2) 关闭 NewUI 公开自注册（`register_enabled`/`password_register_enabled`）防白嫖（因 QuotaForNewUser 全局作用于自注册）。文档记录为部署前置，不写进功能代码。
- **Rationale**：QuotaForNewUser 全局副作用须用关自注册兜底；账号只应经 Nexcompute 邀请门控分配。

### D13. POST /api/user/ 无 id 返回 -> search 回查回填 newapi_user_id（NewAPI 特有）
- **Decision**：`POST /api/user/` 响应仅 `{success:true}` 无 id；`userCreate` 内部建后立即 `GET /api/user/search?keyword=<username>` 回查拿 id（用户名唯一，search 总命中），回填 `newapi_registration.newapi_user_id`。
- **Rationale**：建用户响应无 id 为 NewAPI rc.22 实测行为；search 回查是唯一拿 id 的途径。

### D14. 改分组 PUT /api/user/ body 须含 id+username，不改 quota（NewAPI 特有）
- **Decision**：`reapprove`（改分组）调 `PUT /api/user/`，body 含 `id`+`username`+`group`（缺 username 报 `Invalid parameters`）。不改 quota（PUT 不生效）。对应 nas `reapprove` 的 `PUT /user/id/{id}` 改组。
- **Rationale**：NewAPI PUT 的 username 必填约束与 nas 的 path-id 风格不同，需在客户端封装时带 username。

## Risks

- **系统访问令牌轮换**：`GET /api/user/token` 会废当前令牌；`NewApiClient` 方法集显式排除该端点，javadoc 警示；`NEWAPI_ACCESS_TOKEN` 经 `docker-compose` `environment:` 注入（真实 compose gitignored），轮换后需更新 compose 文件并重启容器。
- **QuotaForNewUser 全局副作用**：也作用于 NewAPI 公开自注册；必须关 NewUI 自注册（D12），否则外人自注册白嫖大额度。部署 checklist 强制。
- **NewAPI 用户名规则未知边界**：POSIX 约束（nas）不适用；需 spike 验证 NewAPI 对 username 字符集/长度/保留字约束，据此定服务端正则。
- **合规闸门**：若未来要 per-user 额度（非大值默认），需开 `compliance_confirmed` + `topup/complete`（body 待定）；当前 D11 方案不依赖。
- **NewAPI rc.22 版本特性**：POST 无 id、PUT 不改 quota、无 unlimited、QuotaForNewUser 对 admin 建用户生效--均为该版本实测行为（记忆 [[newapi-user-quota-quirks]]）；升级 NewAPI 版本需复核。
- **Flyway SQL 禁 `${`**：建表 SQL 注释/字符串均不得出现 `${`；`ddl-auto=validate` 下字段/类型与 JPA 实体精确对齐。
- **`group` 分组取值**：分组由 NewUI 配置（如 `default`/`vip`），批准时下拉选项需与 NewAPI 实例实际分组一致；本期不做分组动态拉取（硬编码常用项或后续加 `GET /api/user/group`）。
