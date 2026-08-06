## 1. 数据库迁移（Flyway V32）

- [x] 1.1 新建 `nas_invitation` 表（`id BIGSERIAL PK`、`token VARCHAR(64) UNIQUE NOT NULL`、`label VARCHAR(128) NOT NULL`、`max_uses INT NOT NULL DEFAULT 1`、`used_count INT NOT NULL DEFAULT 0`、`expires_at TIMESTAMPTZ NOT NULL`、`created_by BIGINT NOT NULL REFERENCES app_user(id)`、`created_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`revoked_at TIMESTAMPTZ NULL`）+ 索引 `ix_nas_invitation_token`
- [x] 1.2 新建 `nas_registration` 表（`id BIGSERIAL PK`、`invitation_id BIGINT NOT NULL REFERENCES nas_invitation(id)`、`username VARCHAR(64) NOT NULL`、`full_name VARCHAR(255) NOT NULL`、`email VARCHAR(255) NOT NULL`、`phone VARCHAR(64) NOT NULL`、`password_enc TEXT NULL`、`status VARCHAR(16) NOT NULL DEFAULT 'PENDING'`、`truenas_user_id INT NULL`、`truenas_uid INT NULL`、`submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`reviewed_by BIGINT NULL REFERENCES app_user(id)`、`reviewed_at TIMESTAMPTZ NULL`、`reject_reason VARCHAR(255) NULL`、`provision_error TEXT NULL`）+ 索引 `ix_nas_reg_invitation_id`/`ix_nas_reg_username`/`ix_nas_reg_email`/`ix_nas_reg_status`/`ix_nas_reg_status_submitted(status, submitted_at)`
- [x] 1.3 表/字段中文注释（`COMMENT ON`，仿 V13 风格）
- [x] 1.4 全文检查 SQL 不含 `${`（含注释/字符串，记忆：placeholder 未配置致启动失败）；`ddl-auto=validate` 下字段/类型与 JPA 实体精确对齐
- [x] 1.5 迁移可正向执行且幂等；启动后 Hibernate validate 通过

## 2. 后端配置与依赖（D4）

- [x] 2.1 根目录 `.env.example`（TrueNAS key/AES key/DB/JWT/SMTP/NAS_* 占位，入库）+ `.env` 真实值（gitignore）；`.gitignore` 加 `.env`
- [x] 2.2 `application.yml` 加 `spring.config.import: "optional:file:.env[.properties]"` + `nexcompute.nas.*` 默认值（`${ENV:默认}`）
- [x] 2.3 新增 `config/NasProperties`（`@ConfigurationProperties(prefix="nexcompute.nas")`）：`truenas.{baseUrl,apiKey,verifyTls,timeoutSeconds,retries,userHomeParent}`、`passwordEncKey`、`pendingExpireDays`、`expiryScanIntervalHours`、`portalBaseUrl`
- [x] 2.4 `docker-compose.yml` 的 `backend` 改 `env_file: [.env]`（与既有 `environment:` 并存或替换，保留无 `.env` 时的回退）
- [x] 2.5 `build.gradle.kts` 确认无需新依赖（`RestClient` 在 spring-web 内置）；如需 `spring-boot-starter` 已含
- [x] 2.6 验证无 `.env` 时仍可 `gradlew bootRun` 启动（回退默认/系统环境）

## 3. 领域与仓库（D2）

- [x] 3.1 `domain/NasInvitation.java`（JPA `@Entity`，字段对齐 1.1；`isValid()`/`isExpired()` 便捷方法）
- [x] 3.2 `domain/NasRegistration.java`（JPA `@Entity`，字段对齐 1.2；`status` 枚举或常量 `PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND`）
- [x] 3.3 `repository/NasInvitationRepository`：`findByToken`、`findByRevokedAtIsNull...`、`@Modifying @Query redeem(token)` 条件 UPDATE（D8）
- [x] 3.4 `repository/NasRegistrationRepository`：按 status 列表、按 username + status-in 查重、`APPROVED` 且 `truenasUserId` 非空列表（刷新用）
- [x] 3.5 验证 `@Modifying redeem` 返回受影响行数；0 即名额耗尽/过期/撤销

## 4. TrueNasClient（D6/D10）

- [x] 4.1 新增 `service/TrueNasClient`（Spring `RestClient.builder()`，`baseUrl` + 默认 `Authorization: Bearer ${apiKey}` header）
- [x] 4.2 自签 TLS 关校验：自定义 `ClientHttpRequestFactory`（信任全部的 `SSLContext`）或 `RestClientCustomizer`，仅 `verifyTls=false` 时生效
- [x] 4.3 方法 1:1 对照 Python 版：`corePing()`、`userFindByUsername(username)`、`userCreate(...)`、`userGetInstance(id)`、`userUpdate(id, fields)`
- [x] 4.4 错误分类：`TrueNasConnectionError`（网络/超时）、`TrueNasPermissionError`（401/403/权限文本）、`TrueNasApiError`（含 `httpStatus`，404 用于 NOT_FOUND 翻转）
- [x] 4.5 `@EventListener(ApplicationReadyEvent)` 启动 ping 记 WARN 不阻断（D10）
- [x] 4.6 验证对 TrueNAS（http://10.13.66.23）建/查/改用户可走通（开发环境真实联调或 mock）

## 5. AES-GCM 密码加密（D7）

- [x] 5.1 新增 `service/NasPasswordEncryptor`：`Cipher.getInstance("AES/GCM/NoPadding")`，12B `SecureRandom` nonce，密文 `base64-urlsafe(nonce + ct + tag)`
- [x] 5.2 `encrypt(plaintext)` / `decrypt(token)`；密钥 base64 解码后校验 16/24/32 字节
- [x] 5.3 验证与 Python 版密文格式互通（用同一 `PASSWORD_ENC_KEY` 加解密同一明文一致）

## 6. 邀请服务与接口（D5/D8）

- [x] 6.1 `service/NasInvitationService`：`create(label, maxUses, expireAt)`（`SecureRandom` token，回填 `created_by`，拼完整注册链接）、`list()`、`get(id)`、`revoke(id)`
- [x] 6.2 `controller/NasInvitationController` @ `/admin/nas-invitations`（POST/GET/GET{id}/POST{id}/revoke），`requireAdmin()` + `@Audited`
- [x] 6.3 `ErrorCode` 新增：`NAS_INVITATION_EXPIRED`/`NAS_INVITATION_REVOKED`/`NAS_INVITATION_EXHAUSTED`/`NAS_INVITATION_NOT_FOUND` 等
- [x] 6.4 验证非管理员被拒、token 不可猜测、注册链接正确拼接

## 7. 公开注册服务与接口（D5）

- [x] 7.1 `service/NasRegistrationService.submit(token, username, fullName, email, phone, password)`：校验 token -> 用户名占用查重 -> 原子 redeem 名额 -> AES 加密密码 -> 写 `PENDING` 行（同事务）
- [x] 7.2 `controller/NasPublicRegisterController` @ `/nas-allocation/register`（GET `?token=` 校验返回表单元数据、POST 提交），permitAll
- [x] 7.3 `SecurityConfig` permitAll 加 `/nas-allocation/register/**`
- [x] 7.4 服务端强校验：username POSIX 正则、密码 ≥8 含字母+数字、必填（`@Valid`）
- [x] 7.5 用户名占用规则：仅 `PENDING/APPROVED/FAILED` 占用，`REJECTED` 释放
- [x] 7.6 验证名额耗尽/过期/撤销/用户名占用各自精确报错；不调 TrueNAS

## 8. 审批服务与接口（核心状态机）

- [x] 8.1 `service/NasRegistrationService`：`list(status)`、`getDetail(id)`（本地行 + TrueNAS `userGetInstance` 拼合，404 翻 `NOT_FOUND`）、`approve(id, webuiGroupId)`（解密 -> 幂等查重 -> 建/回填 -> 擦密码 -> `APPROVED`；失败 `FAILED` 保留密码）、`reject(id, reason)`、`reapprove(id, webuiGroupId)`（改组保留 `builtin_users`）、`reprovision(id, password, webuiGroupId)`、`refreshAllStatuses()`、`delete(id)`
- [x] 8.2 `controller/NasRegistrationController` @ `/admin/nas-registrations`（GET 列表、GET{id} 详情、POST{id}/approve、POST{id}/reapprove、POST{id}/reprovision、POST{id}/reject、DELETE{id}、POST /refresh-statuses），`requireAdmin()` + `@Audited`
- [x] 8.3 Web 后台角色组常量：`FULL_ADMIN=40`/`READONLY_ADMIN=41`/`SHARING_ADMIN=42`，批准/改角色/重申上游时透传 `groups:[webuiGroupId]`
- [x] 8.4 幂等：重复批准 `APPROVED` 行无副作用；`FAILED` 可重试；`NOT_FOUND` 仅可重申上游
- [x] 8.5 验证 5 态迁移正确、密码在 `APPROVED`/`REJECTED`/过期后擦除、`FAILED` 保留、`provision_error` 记录

## 9. pending 过期扫描调度（D9）

- [x] 9.1 新增 `service/NasAllocationScheduler`：`@Scheduled` 每 `nascompute.nas.expiry.scan-interval-hours`（默认 6）扫描 `PENDING AND submitted_at < now - expireDays`
- [x] 9.2 命中行擦 `password_enc` 并置 `REJECTED`
- [x] 9.3 验证扫描不阻塞业务、幂等、日志可查

## 10. 前端（Vue 3 / AntD）

- [x] 10.1 `views/admin/NasAllocationView.vue`（`a-tabs`：邀请管理 / 审批队列 / 已开通用户），详情/批准/拒绝/改角色/重申上游/删除弹窗 + Toast + 状态徽章
- [x] 10.2 邀请管理 tab：创建表单（label/maxUses/expireAt）+ 列表 + 撤销 + 注册链接复制
- [x] 10.3 审批队列 tab：PENDING 列表 + 批准（选角色下拉 40/41/42/无）+ 拒绝（原因）+ 删除
- [x] 10.4 已开通用户 tab：APPROVED/NOT_FOUND 列表 + 改角色 + 重申上游（新密码）+ 批量刷新状态
- [x] 10.5 `views/auth/NasRegisterView.vue`（公开路由）：token 校验态 + 表单 + 「待审批」成功页
- [x] 10.6 `api/nasAllocation.ts`：邀请/注册/审批全部接口封装
- [x] 10.7 `router/index.ts` 加 `/admin/nas-allocation`（ADMIN）+ `/nas-register`（public）
- [x] 10.8 `layouts/BasicLayout.vue` 菜单加 `{ key:'/admin/nas-allocation', label:'NAS分配', icon: CloudServerOutlined, roles:['ADMIN'] }`
- [x] 10.9 验证三角色菜单可见性、表单校验、加载/等待提示、自定义 Modal（无浏览器 alert/confirm）

## 11. 端到端验证

- [x] 11.1 创建邀请 -> 公开链接注册 -> 名额正确消耗 -> PENDING 行写入（密码加密）
- [x] 11.2 并发兑换不超发（条件 UPDATE 生效）
- [x] 11.3 批准 -> TrueNAS 建用户 -> 回填 id/uid -> 擦密码 -> APPROVED；幂等重批无副作用
- [x] 11.4 批准失败（断 TrueNAS/缺权限）-> FAILED 保留密码 -> 重试成功
- [x] 11.5 拒绝 -> 擦密码 -> REJECTED -> 名额不退 -> 用户名释放
- [x] 11.6 改角色 -> TrueNAS 用户组变更保留 builtin_users
- [x] 11.7 TrueNAS 删用户 -> 详情翻 NOT_FOUND -> 重申上游 -> APPROVED
- [x] 11.8 批量刷新状态 -> 404 翻 NOT_FOUND，网络错误不翻
- [x] 11.9 pending 超 7 天 -> 扫描置 REJECTED + 擦密码
- [x] 11.10 非管理员访问 `/admin/nas-*` 被拒；公开注册免鉴权
- [x] 11.11 `.env` 真实密钥注入生效；无 `.env` 可回退默认启动；docker-compose `env_file` 同源
- [x] 11.12 审计日志记录所有管理员操作（`@Audited`）
- [x] 11.13 `openspec validate nas-allocation --strict` 通过
