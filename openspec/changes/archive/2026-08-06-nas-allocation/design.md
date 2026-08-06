## Context

`D:\NAS管理` 下有一个已上线（开发环境）的独立 TrueNAS 用户开通门户，需整体并入 Nexcompute 管理端。关键现状（经两库代码确认）：

**源端（NAS 管理，Python）**
- FastAPI 同步栈 + SQLAlchemy 2.0 同步 + Alembic + 独立 `nas` 库（10.13.66.18:5432）；Jinja2 SSR + 原生 JS；httpx 调 TrueNAS REST；`cryptography` AES-GCM 暂存密码；bcrypt + 自建 `admins` 表 + JWT。
- 三表：`admins`/`invitations`/`registrations`；5 态机 `PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND`。
- TrueNAS REST（`/api/v2.0`，Bearer key，verify=false）：`POST /user`（建）、`GET /user?username=&local=true&limit=1`（查重）、`GET /user/id/{id}`（详情）、`PUT /user/id/{id}`（改组）、`GET /core/ping`。
- Web 后台角色组：`builtin_administrators=40`（FULL_ADMIN）/ `truenas_readonly_administrators=41`（READONLY）/ `truenas_sharing_administrators=42`（SHARING）。
- 名额原子消耗：单条 `UPDATE ... SET used_count=used_count+1 WHERE used_count<max_uses AND revoked_at IS NULL AND expires_at>now`。
- AES 密文格式：`base64-urlsafe(12B nonce + ciphertext + 16B tag)`。
- 三份 `.env.*`（truenas/db/app）均 gitignored。

**目标端（Nexcompute，已确认）**
- SpringBoot 3.5 + Java 21 + Spring Data JPA（`ddl-auto=validate`，schema 仅经 Flyway，当前 V31）+ PostgreSQL + jjwt + Spring Security + Spring Mail + Lombok。
- 前端 Vue 3 + Vite + TS + Ant Design Vue（`a-layout`/`a-menu`/`Modal`/`a-tabs`）；`router/index.ts` 已有 `/register`、`/mentor-register`、`/admin-register` 公开路由模式 + `meta.roles:['ADMIN']` 管理路由模式。
- 平台已有 `RegistrationLink`（token + remainingCount + expireAt + status + linkType）+ `MentorRegistrationService`/`AdminRegistrationService` 邀请链接先例；`User`/`UserRole={ADMIN,MENTOR,STUDENT}`；`@Audited` AOP 切面 + `SecurityUtils.getCurrentRole()/getCurrentUserId()`；`ApiResponse`/`BusinessException`/`ErrorCode`/`GlobalExceptionHandler`。
- `SecurityConfig` permitAll 列 `/auth/login`、`/auth/register`、`/auth/mentor-register`、`/auth/admin-register`；其余 authenticated。
- `system_config` 键值表 + `NexcomputeProperties`（`@ConfigurationProperties(prefix="nexcompute")`）+ `application.yml` 用 `${ENV:default}`。
- `@Scheduled` 已用（`HeartbeatTimeoutScheduler`）；`@EnableAsync`/`@EnableScheduling` 在 `ManagementApplication`。
- 当前**无 `.env` 文件**；docker-compose `backend` 用 `environment:` 直注。
- 记忆：Flyway SQL 含 `${...}`（含注释/字符串）会致启动失败（placeholder 未配置）。

## Goals / Non-Goals

**Goals:**
- 管理员后台「NAS分配」入口：邀请管理 + 审批 + 已开通用户管理，全流程留痕。
- 邀请门控的公开注册：名额原子消耗、AES-GCM 密码暂存、pending 过期自动清理。
- 审批开通经 TrueNAS REST 自动建用户，支持改角色/重申上游/批量刷新状态。
- 全栈迁至 SpringBoot + Vue，并入 `nexcompute` 库（单 DataSource / 单 Flyway），复用平台 ADMIN 鉴权。
- TrueNAS 连接、AES 密钥、DB 等配置统一进根 `.env`；`.env.example` 入库作模板。
- 原门户 5 态机 / TrueNAS REST 调用语义 / 名额原子性 1:1 保留。

**Non-Goals:**
- 不做用户端登录后门户（开通后直接用 TrueNAS Web UI / SMB / SSH）。
- 不做短信/邮件推送（平台已有 `email-notification`，本期不接入）。
- 不做 TrueNAS 共享/配额/权限配置（仅开通用户账号与 Web 后台角色）。
- 不做 SSO / 2FA / WebSocket 实时事件。
- 不迁原 `nas` 库历史数据（全新开始）；不保留原 Python 门户。
- 不做 SMTP 密码加密存储之外的密钥加密（`.env` 本地保管 + gitignore）。

## Decisions

### D1. 并入 nexcompute 库，单 DataSource（而非第二 DataSource 连 nas 库）
- **Decision**：新建 `nas_invitation`/`nas_registration` 两表，经 Flyway `V32` 建在 `nexcompute` 库；复用既有 DataSource / Flyway / JPA `validate`。原 `nas` 库弃用。
- **Alternatives**：第二 DataSource 连 `10.13.66.18:5432/nas` 保留原库数据--rejected，3 张小表不值得背多 DataSource + 多 Flyway location + 多事务管理器复杂度，且与"并入管理端"语义相悖。
- **Rationale**：用户明确选择（岔路①A）；单库最干净，`ddl-auto=validate` 无碍，事务边界简单。

### D2. 新建 nas_ 前缀表，不复用 RegistrationLink（而非扩 linkType=NAS）
- **Decision**：`nas_invitation` / `nas_registration` 独立表，`created_by`/`reviewed_by` FK→`app_user.id`。砍掉原 `admins` 表。
- **Alternatives**：扩 `RegistrationLink` 加 `linkType=NAS` + label 列--rejected，NAS 邀请产物是外部 TrueNAS 用户（非平台 `User`），与平台注册链接语义不同；耦合会令 redemption 逻辑按 linkType 长期分叉，label 列对其他 linkType 是噪音。
- **Rationale**：用户明确选择（岔路②A）；关注点分离，NAS 5 态机与 TrueNAS 集成自成一体不污染平台注册链接。

### D3. 全新开始，不写数据迁移脚本
- **Decision**：`V32` 仅 CREATE TABLE + 索引 + 注释，不 SELECT/INSERT 原库；AES key 可重新生成（密文格式仍与 Python 版一致以备日后对照）。
- **Alternatives**：迁原 `nas` 库 invitations/registrations 行 + 复用原 AES key 解密--rejected，开发环境数据无需保留，省去跨库迁移脚本与密钥绑定。
- **Rationale**：用户明确选择（岔路③全新开始）。

### D4. .env 走 Spring Boot 原生导入 + docker-compose env_file 兼容
- **Decision**：根目录 `.env`（gitignore）+ `.env.example`（入库）；`application.yml` 加 `spring.config.import: "optional:file:.env[.properties]"`；docker-compose `backend` 改 `env_file: [.env]`。新增 `NasProperties`（`nexcompute.nas.*`）读 `TRUENAS_*` / `PASSWORD_ENC_KEY` / `NAS_*` 键；`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`JWT_SECRET`/`EMAIL_*` 已在环境，仅在 `.env.example` 留占位。
- **Alternatives**：(a) 仅 docker-compose `env_file`，Spring 不直读 .env--rejected，本地 IDE 跑/`gradlew bootRun` 读不到；(b) 仅 Spring 导入，docker-compose 仍用 `environment:`--可行但两处维护，故选兼容双读。
- **Rationale**：用户确认"按你的来"；`.env` 作单一真实源，本地与容器同源；`optional:` 保证无 `.env` 时仍可用默认值/系统环境启动。
- **修正（实现时发现）**：`.env` 值**不要加引号**。`spring.config.import` 以 properties 解析，Spring 的 `OriginTrackedPropertiesLoader` **不剥离引号**——`DB_PASSWORD="nexcompute"` 会被读成带引号的 `"nexcompute"` 致 PG 认证失败、`PASSWORD_ENC_KEY="...="` 致 base64 解码失败。`=`（首个分隔符之后为字面）与 `@` 在 properties 值中均非特殊字符，无需引号。`docker-compose` `env_file` 同理不剥引号。

### D5. 复用平台 ADMIN 鉴权，砍 admins 表 / CLI / bootstrap
- **Decision**：管理员端接口走平台 JWT + `SecurityUtils.getCurrentRole()==ADMIN` 校验；公开注册接口加进 `SecurityConfig.permitAll`（`/nas-allocation/register/**`）。`created_by`/`reviewed_by` FK→`app_user.id`。
- **Alternatives**：保留独立 `admins` 表 + 独立 JWT--rejected，与"并入管理端"相悖，双登录体验差。
- **Rationale**：平台已有 ADMIN 体系，无需重复。

### D6. TrueNasClient 用 Spring RestClient（同步），非 WebClient
- **Decision**：`RestClient`（Spring 6.1 同步 builder），Bearer 认证，自签 TLS 配信任全部的 `SSLContext`。方法 1:1 对照 Python 版（`corePing`/`userCreate`/`userFindByUsername`/`userGetInstance`/`userUpdate`）。
- **Alternatives**：(a) WebClient（reactive）--rejected，整栈同步（JPA 同步），引入 reactive 徒增复杂；(b) Java 11 `HttpClient`--可用但不如 RestClient Spring 惯用。
- **Rationale**：同步栈匹配；RestClient 是 SpringBoot 3.x 推荐的同步 HTTP 客户端。

### D7. AES-GCM 用 javax.crypto，密文格式与 Python 版完全一致
- **Decision**：`NasPasswordEncryptor` 用 `Cipher.getInstance("AES/GCM/NoPadding")`，12B 随机 nonce，密文 = `base64-urlsafe(nonce + cipher.doFinal(plaintext))`（Java GCM 默认 tag 在尾部，与 Python `AESGCM.encrypt` 输出一致）。
- **Alternatives**：改用 Spring Security `BytesKeyGenerator` + JCA 封装--可行但多一层抽象，无收益。
- **Rationale**：1:1 对照原实现便于核对；全新开始虽不强制兼容，但格式统一利于日后跨栈排查或迁移。

### D8. 名额原子消耗用 @Modifying @Query 条件 UPDATE
- **Decision**：`NasInvitationRepository.redeem(token)` 用 `@Modifying @Query("UPDATE NasInvitation i SET i.usedCount = i.usedCount + 1 WHERE i.token = :token AND i.usedCount < i.maxUses AND i.revokedAt IS NULL AND i.expiresAt > now")`，返回受影响行数；0 即名额耗尽/过期/撤销。注册事务内先 redeem 再写 registration，同 commit。
- **Alternatives**：先 `findByToken` 校验再 `save`--rejected，并发会超发（读后写竞态）。
- **Rationale**：原 Python 版即单条条件 UPDATE 保并发安全，JPA 等价做法是 `@Modifying` 条件 UPDATE。

### D9. pending 过期扫描用 @Scheduled，复用既有调度
- **Decision**：`NasAllocationScheduler` 用 `@Scheduled(fixedDelayString = "${nascompute.nas.expiry.scan-interval-hours:6}h"...` 或 `fixedDelay` 毫秒），扫描 `status=PENDING AND submitted_at < now - expireDays` 的行，擦 `password_enc` 并置 `REJECTED`。
- **Alternatives**： Quartz / 守护线程--rejected，`@Scheduled` 已在用（`HeartbeatTimeoutScheduler`），无需新依赖。
- **Rationale**：复用平台既有调度模式。

### D10. 启动 ping TrueNAS 仅告警不阻断
- **Decision**：`@EventListener(ApplicationReadyEvent)` 调 `corePing`，失败记 WARN 日志，不抛异常（启动继续）。`user.create` 在 `approve` 时若 key 权限不足（ACCOUNT_WRITE）才暴露为 `FAILED`。
- **Alternatives**：启动校验失败即拒绝启动--rejected，TrueNAS 不可达不应令整个管理端起不来。
- **Rationale**：原 Python 版即"记日志不阻断"；TrueNAS REST 无 `auth.me`，ACCOUNT_WRITE 本就不可预检。

## Risks

- **TrueNAS 自签 TLS**：`RestClient` 需配信任全部的 `SSLContext`（dev 为 `http://` 无碍，prod 必踩）；实现时封装 `RestClientCustomizer` 或自定义 `ClientHttpRequestFactory`。
- **ACCOUNT_WRITE 不可预检**：key 权限不足到 `approve` 调 `user.create` 才暴露，前端需在 `FAILED` 时展示 `provision_error`。
- **Flyway SQL 禁 `${`**：建表 SQL 注释/字符串均不得出现 `${`（记忆：placeholder 未配置致启动失败）。
- **`ddl-auto=validate`**：`V32` 建的表必须与 JPA 实体字段/类型精确对齐，否则启动 validate 失败。
- **.env 特殊字符**：AES key 含尾部 `=`、PG 密码含 `@`，`.env` 中值须加引号；`spring.config.import` 以 properties 解析，引号会被剥离。
- **Web UI 角色组 id 硬编码 40/41/42**：TrueNAS 实例间通常一致（builtin），但非 builtin 组场景需管理员在 TrueNAS 侧维护；本期不做组 id 可配置。
- **密码策略与 POSIX 用户名校验**：注册接口须服务端强校验（`@Valid` + 正则），前端校验仅为体验。
