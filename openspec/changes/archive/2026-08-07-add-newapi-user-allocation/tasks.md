## 1. 数据库迁移（Flyway V33）

- [x] 1.1 新建 `newapi_invitation` 表（`id BIGSERIAL PK`、`token VARCHAR(64) UNIQUE NOT NULL`、`label VARCHAR(128) NOT NULL`、`max_uses INT NOT NULL DEFAULT 1`、`used_count INT NOT NULL DEFAULT 0`、`expires_at TIMESTAMPTZ NOT NULL`、`created_by BIGINT NOT NULL REFERENCES app_user(id)`、`created_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`revoked_at TIMESTAMPTZ NULL`）+ 索引 `ix_newapi_invitation_token`
- [x] 1.2 新建 `newapi_registration` 表（`id BIGSERIAL PK`、`invitation_id BIGINT NOT NULL REFERENCES newapi_invitation(id)`、`username VARCHAR(64) NOT NULL`、`display_name VARCHAR(255) NOT NULL`、`email VARCHAR(255) NOT NULL`、`phone VARCHAR(64) NOT NULL`、`password_enc TEXT NULL`、`status VARCHAR(16) NOT NULL DEFAULT 'PENDING'`、`newapi_user_id INT NULL`、`newapi_group VARCHAR(64) NULL`、`submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`reviewed_by BIGINT NULL REFERENCES app_user(id)`、`reviewed_at TIMESTAMPTZ NULL`、`reject_reason VARCHAR(255) NULL`、`provision_error TEXT NULL`）+ 索引 `ix_newapi_reg_invitation_id`/`ix_newapi_reg_username`/`ix_newapi_reg_email`/`ix_newapi_reg_status`/`ix_newapi_reg_status_submitted(status, submitted_at)`
- [x] 1.3 表/字段中文注释（`COMMENT ON`，仿 V32 风格）
- [x] 1.4 全文检查 SQL 不含 `${`（含注释/字符串，记忆：placeholder 未配置致启动失败）；`ddl-auto=validate` 下字段/类型与 JPA 实体精确对齐
- [x] 1.5 迁移可正向执行且幂等；启动后 Hibernate validate 通过

## 2. 后端配置与依赖（D4）

- [x] 2.1 `docker-compose.example.yml` 与 `docker-compose.prod.example.yml` 的 backend `environment:` 块加 `NEWAPI_BASE_URL`/`NEWAPI_ACCESS_TOKEN`/`NEWAPI_API_USER`/`NEWAPI_TIMEOUT_SECONDS`/`NEWAPI_RETRIES` 占位；真实 `docker-compose.yml`/`.prod.yml` 填真实值（gitignored）
- [x] 2.2 `application.yml` 加 `nexcompute.newapi.*` 默认值（`${ENV:默认}`）
- [x] 2.3 新增 `config/NewApiProperties`（`@ConfigurationProperties(prefix="nexcompute.newapi")`）：`baseUrl`/`accessToken`/`apiUser`/`timeoutSeconds`/`retries`/`pendingExpireDays`/`expiryScanIntervalHours`/`portalBaseUrl`
- [x] 2.4 `build.gradle.kts` 确认无需新依赖（`RestClient` 在 spring-web 内置）
- [x] 2.5 验证无环境注入时仍可启动（回退 `application.yml` `${ENV:default}` 默认值）

## 3. 领域与仓库（D2）

- [x] 3.1 `domain/NewApiInvitation.java`（JPA `@Entity`，字段对齐 1.1；`isValid()`/`isExpired()` 便捷方法）
- [x] 3.2 `domain/NewApiRegistration.java`（JPA `@Entity`，字段对齐 1.2；`status` 常量 `PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND`）
- [x] 3.3 `repository/NewApiInvitationRepository`：`findByToken`、`findByRevokedAtIsNull...`、`@Modifying @Query redeem(token)` 条件 UPDATE（D8）
- [x] 3.4 `repository/NewApiRegistrationRepository`：按 status 列表、按 username + status-in 查重、`APPROVED` 且 `newapiUserId` 非空列表（刷新用）
- [x] 3.5 验证 `@Modifying redeem` 返回受影响行数；0 即名额耗尽/过期/撤销

## 4. NewApiClient（D6/D10/D13/D14）

- [x] 4.1 新增 `service/NewApiClient`（Spring `RestClient.builder()`，`baseUrl` + 默认 `Authorization: Bearer ${accessToken}` + `New-Api-User: ${apiUser}` 头）
- [x] 4.2 方法 1:1 对照 `TrueNasClient`：`statusPing()`（`GET /api/status`）、`userSearch(keyword)`、`userCreate(...)`、`userGetInstance(id)`、`userUpdate(id, username, fields)`、`userDelete(id)`
- [x] 4.3 `userCreate` 建后内部 `userSearch` 回查拿 id 返回（D13，建用户响应无 id）
- [x] 4.4 `userUpdate` body 含 `id`+`username`+变更字段（D14，缺 username 报 Invalid parameters）
- [x] 4.5 错误分类：`NewApiConnectionError`/`NewApiPermissionError`（401/403）/`NewApiApiError`（含 `httpStatus`，404 用于 NOT_FOUND 翻转）
- [x] 4.6 `@EventListener(ApplicationReadyEvent)` 启动 `GET /api/status` 记 WARN 不阻断（D10）
- [x] 4.7 **javadoc 警示 + 方法集显式排除 `GET /api/user/token`**（会轮换系统访问令牌，D10）
- [x] 4.8 验证对 NewAPI（10.13.66.18:3001）建/查/改/删用户可走通（真实联调）

## 5. AES-GCM 密码加密（D7，复用）

- [x] 5.1 复用既有 `NasPasswordEncryptor`（AES/GCM/NoPadding，12B nonce，`PASSWORD_ENC_KEY`，密文 `b64url(nonce+ct+tag)`）
- [x] 5.2 javadoc 注明"NAS/NewAPI 共用同一密钥与格式"
- [x] 5.3 验证加解密互通（同 nas，密文格式一致）

## 6. 邀请服务与接口（D5/D8）

- [x] 6.1 `service/NewApiInvitationService`：`create(label, maxUses, expireAt)`（`SecureRandom` token，回填 `created_by`，拼完整注册链接）、`list()`、`get(id)`、`revoke(id)`
- [x] 6.2 `controller/NewApiInvitationController` @ `/admin/newapi-invitations`（POST/GET/GET{id}/POST{id}/revoke），`requireAdmin()` + `@Audited`
- [x] 6.3 `ErrorCode` 新增：`NEWAPI_INVITATION_EXPIRED`/`NEWAPI_INVITATION_REVOKED`/`NEWAPI_INVITATION_EXHAUSTED`/`NEWAPI_INVITATION_NOT_FOUND`/`NEWAPI_INVITATION_INVALID` 等
- [x] 6.4 验证非管理员被拒、token 不可猜测、注册链接正确拼接

## 7. 公开注册服务与接口（D5）

- [x] 7.1 `service/NewApiRegistrationService.submit(token, username, displayName, email, phone, password)`：校验 token -> 用户名占用查重 -> 原子 redeem 名额 -> AES 加密密码 -> 写 `PENDING` 行（同事务，不调 NewAPI）
- [x] 7.2 `controller/NewApiPublicRegisterController` @ `/newapi-allocation/register`（GET `?token=` 校验返回表单元数据、GET `/check-username`、POST 提交），permitAll
- [x] 7.3 `SecurityConfig` permitAll 加 `/newapi-allocation/register/**`
- [x] 7.4 服务端强校验：NewAPI 用户名规则（spike 定字符集/长度）、密码 ≥8 含字母+数字、必填（`@Valid`）
- [x] 7.5 用户名占用规则：仅 `PENDING/APPROVED/FAILED` 占用，`REJECTED` 释放；`check-username` 调 NewAPI `userSearch` 查重（不可达返回 `UNREACHABLE` 不阻断）
- [x] 7.6 验证名额耗尽/过期/撤销/用户名占用/NewAPI 已占 各自精确报错；不调 NewAPI 建用户

## 8. 审批服务与接口（核心状态机）

- [x] 8.1 `service/NewApiRegistrationService`：`list(status)`、`getDetail(id)`（本地行 + NewAPI `userGetInstance` 拼合，404 翻 `NOT_FOUND`）、`approve(id, group)`（解密 -> 幂等查重 -> 建/回填 -> search 回查 id -> 擦密码 -> `APPROVED`；失败 `FAILED` 保留密码）、`reject(id, reason)`、`reapprove(id, group)`（`PUT /api/user/` 改 group，body 含 id+username）、`reprovision(id, password, group)`、`refreshAllStatuses()`、`delete(id)`
- [x] 8.2 `controller/NewApiRegistrationController` @ `/admin/newapi-registrations`（GET 列表、GET{id} 详情、POST{id}/approve、POST{id}/reapprove、POST{id}/reprovision、POST{id}/reject、DELETE{id}、POST /refresh-statuses），`requireAdmin()` + `@Audited`
- [x] 8.3 批准时 group 下拉选项对照 NewAPI 实例实际分组（如 `default`/`vip`）；本期硬编码常用项或后续加 `GET /api/user/group` 动态拉取
- [x] 8.4 幂等：重复批准 `APPROVED` 行无副作用；`FAILED` 可重试；`NOT_FOUND` 仅可重申上游
- [x] 8.5 验证 5 态迁移正确、密码在 `APPROVED`/`REJECTED`/过期后擦除、`FAILED` 保留、`provision_error` 记录
- [x] 8.6 `ErrorCode` 新增：`NEWAPI_REGISTRATION_NOT_FOUND`/`NEWAPI_REGISTRATION_INVALID_STATE`/`NEWAPI_NO_PASSWORD`/`NEWAPI_PASSWORD_DECRYPT_FAILED`/`NEWAPI_PROVISION_FAILED`/`NEWAPI_USERNAME_TAKEN` 等

## 9. pending 过期扫描调度（D9）

- [x] 9.1 新增 `service/NewApiAllocationScheduler`：`@Scheduled` 每 `nexcompute.newapi.expiry.scan-interval-hours`（默认 6）扫描 `PENDING AND submitted_at < now - expireDays`
- [x] 9.2 命中行擦 `password_enc` 并置 `REJECTED`
- [x] 9.3 验证扫描不阻塞业务、幂等、日志可查

## 10. 前端（Vue 3 / AntD）

- [x] 10.1 `views/admin/NewApiUserAllocationView.vue`（`a-tabs`：邀请管理 / 审批队列 / 审批历史 / 申请记录），详情/批准/拒绝/改分组/重申上游/删除弹窗 + Toast + 状态徽章
- [x] 10.2 邀请管理 tab：创建表单（label/maxUses/expireAt）+ 列表 + 撤销 + 注册链接复制
- [x] 10.3 审批队列 tab：PENDING 列表 + 批准（选 group 下拉）+ 拒绝（原因）+ 删除
- [x] 10.4 审批历史 tab：所有已处理记录，含审批人与审批时间
- [x] 10.5 申请记录 tab：APPROVED/NOT_FOUND 列表 + 改分组 + 重申上游（新密码）+ 批量刷新状态
- [x] 10.6 `views/auth/NewApiRegisterView.vue`（公开路由）：token 校验态 + 表单 + 用户名 onBlur 查重 + 「待审批」成功页
- [x] 10.7 `api/newApiUserAllocation.ts`：邀请/注册/审批全部接口封装
- [x] 10.8 `router/index.ts` 加 `/admin/newapi-user-allocation`（ADMIN）+ `/newapi-register`（public）
- [x] 10.9 `layouts/BasicLayout.vue` 菜单加「Token分配」（ADMIN）
- [x] 10.10 验证菜单可见性、表单校验、加载/等待提示、自定义 Modal（无浏览器 alert/confirm）

## 11. 端到端验证

- [x] 11.1 创建邀请 -> 公开链接注册 -> 名额正确消耗 -> PENDING 行写入（密码加密）
- [x] 11.2 并发兑换不超发（条件 UPDATE 生效）
- [x] 11.3 批准 -> NewAPI 建用户 -> search 回查回填 newapi_user_id -> 擦密码 -> APPROVED；幂等重批无副作用
- [x] 11.4 批准失败（断 NewAPI/令牌失效）-> FAILED 保留密码 -> 重试成功
- [x] 11.5 拒绝 -> 擦密码 -> REJECTED -> 名额不退 -> 用户名释放
- [x] 11.6 改分组 -> NewAPI 用户 group 变更（quota 不变）
- [x] 11.7 NewAPI 删用户 -> 详情翻 NOT_FOUND -> 重申上游 -> APPROVED
- [x] 11.8 批量刷新状态 -> 404 翻 NOT_FOUND，网络错误不翻
- [x] 11.9 pending 超 7 天 -> 扫描置 REJECTED + 擦密码
- [x] 11.10 非管理员访问 `/admin/newapi-*` 被拒；公开注册免鉴权
- [x] 11.11 docker-compose `environment:` 注入生效；无注入时回退 `application.yml` 默认值；模板 `.example.yml` 与真实 compose 同源
- [x] 11.12 审计日志记录所有管理员操作（`@Audited`）
- [x] 11.13 `openspec validate add-newapi-user-allocation --strict` 通过

## 12. 运维前置（NewAPI 侧一次性，非功能代码，D11/D12）

- [x] 12.1 `PUT /api/option/ {"key":"QuotaForNewUser","value":"<大值>"}` 设新用户默认额度（如 `100000000`，"实际无限"）
- [x] 12.2 关闭 NewUI 公开自注册（`register_enabled`/`password_register_enabled`）防白嫖（QuotaForNewUser 全局副作用）
- [x] 12.3 文档/部署 checklist 记录此两项为功能启用前置；功能代码不依赖 per-user 额度授予
- [x] 12.4 验证设 QuotaForNewUser 后 `POST /api/user/` 建用户自动带额度（实测非合规门控）
