## Why

TrueNAS 用户账号原本由管理员在 Web UI 手动逐个创建，存在三个问题：无审批留痕、无可控邀请（无法限定名额/有效期）、无业务侧元信息（姓名/手机号/邮箱）。`D:\NAS管理` 下已有一个独立 Python/FastAPI 门户解决了该问题（邀请链接门控注册 + 管理员审批 + TrueNAS REST 自动开通），但它是独立栈、独立 `nas` 库、独立鉴权，与 Nexcompute 管理端割裂。需将其整体迁入 Nexcompute 管理端，成为管理员后台「NAS分配」模块：复用平台 ADMIN 鉴权与 `nexcompute` 库，密钥/TrueNAS 连接统一进 `.env`，技术栈全部切到 SpringBoot + Vue。原独立门户废弃。

## What Changes

**邀请门控注册（NAS 用户管理）**
- 新建 `nas_invitation` 表（token / label / max_uses / used_count / expires_at / created_by→app_user.id / revoked_at），管理员创建/列表/详情/撤销。
- token 用 `SecureRandom` 生成不可猜测串，返回完整注册链接 `{NAS_PORTAL_BASE_URL}/nas-register?token={token}`。
- 名额原子消耗：`@Modifying @Query` 条件 UPDATE（`used_count < max_uses AND revoked_at IS NULL AND expires_at > now`），并发不超发；过期/撤销/名额耗尽各自精确报错。

**注册申请（公开）**
- 新增公开 Vue 路由 `/nas-register?token=` + 公开 API `POST /api/nas-allocation/register`，服务端校验 token 后接受表单（username POSIX / 姓名 / 邮箱 / 手机号 / 密码 ≥8 位含字母+数字）。
- 提交：原子消耗名额 → AES-GCM 加密密码（密钥 `PASSWORD_ENC_KEY`，密文格式 `b64url(nonce12 + ct + tag)`）→ 写 `PENDING` 行（**不调 TrueNAS**）→ 返回「待审批」。
- 用户名占用：`PENDING/APPROVED/FAILED` 占用的用户名不可再注册（仅 `REJECTED` 释放）。
- pending 过期（默认 7 天）由 `@Scheduled` 扫描，擦除密码并置 `REJECTED`。

**审批（管理员）**
- 新建 `nas_registration` 表，状态机 `PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND`。
- 批准并开通：解密密码 → TrueNAS 幂等查重 → 不存在则 `user.create` → 回填 id/uid → 擦除密码 → `APPROVED`；失败置 `FAILED` 保留密码可重试。批准时管理员选 TrueNAS Web 后台角色（完全/只读/共享管理员/无，组 id 40/41/42）。
- 拒绝：擦除密码 → `REJECTED`（不退名额）。删除记录（不删 TrueNAS 用户）。改角色（已开通）：`user.update` 改附加组，保留 `builtin_users`。
- 用户不存在（TrueNAS 上用户已删）：详情自动翻转 `NOT_FOUND`，可「重申上游」用新密码重新注册。点「详情」/「刷新状态」批量复核所有已开通用户在 TrueNAS 是否还在。

**TrueNAS 集成**
- 新增 `TrueNasClient`（Spring `RestClient`，同步），Bearer API key 认证，自签 TLS 关校验。方法：`corePing` / `userCreate` / `userFindByUsername` / `userGetInstance` / `userUpdate`。
- 新用户默认 SMB 开启；SSH 仅当 `TRUENAS_USER_HOME_PARENT` 设置时开启（home 建 `/mnt/.../`）。
- 启动时 `ApplicationReadyEvent` ping TrueNAS 记日志告警，不阻断启动（ACCOUNT_WRITE 不可预检，到 `approve` 才暴露）。

**鉴权与配置**
- 砍掉原门户的 `admins` 表 / CLI / bootstrap：复用平台 `app_user`（role=ADMIN）+ 平台 JWT。`created_by`/`reviewed_by` 改 FK→`app_user.id`。
- 所有管理员端接口走平台 JWT + `SecurityUtils.getCurrentRole()==ADMIN` 校验 + `@Audited` 留痕；公开注册接口加进 `SecurityConfig.permitAll`。
- 根目录新增 `.env`（gitignore）+ `.env.example`（入库）；`application.yml` 加 `spring.config.import: optional:file:.env[.properties]`；新增 `NasProperties`（`nexcompute.nas.*`）读 TrueNAS 连接 / AES 密钥 / 过期策略。docker-compose 的 backend 改 `env_file: [.env]` 读同一份。

## Capabilities

### New Capabilities
- `nas-allocation`: 管理员后台「NAS分配」模块。邀请令牌门控的 TrueNAS 用户注册 + 管理员审批开通全流程，并入 Nexcompute SpringBoot+Vue 栈与 `nexcompute` 库，复用平台 ADMIN 鉴权。包含：邀请管理（创建/列表/撤销、原子名额消耗）、公开注册（AES-GCM 密码暂存、用户名占用、pending 过期扫描）、审批（批准开通/改角色/重申上游/拒绝/删除/批量刷新状态、TrueNAS REST 集成、5 态机）、TrueNAS 自签 TLS 客户端、统一 `.env` 配置（TrueNAS key/AES key/DB/JWT 等）、启动健康检查。

## Impact

**管理端后端（SpringBoot）**
- 新增 `domain/NasInvitation.java`、`domain/NasRegistration.java`；`repository/NasInvitationRepository`、`NasRegistrationRepository`（含 `@Modifying @Query` 原子名额 UPDATE）。
- 新增 `service/NasInvitationService`、`NasRegistrationService`、`TrueNasClient`（`RestClient`）、`NasPasswordEncryptor`（`javax.crypto` AES-GCM）、`NasAllocationScheduler`（`@Scheduled` 过期扫描）。
- 新增 `controller/NasInvitationController`（`/admin/nas-invitations`）、`NasRegistrationController`（`/admin/nas-registrations`）、`NasPublicRegisterController`（`/nas-allocation/register`，公开）。
- 新增 `config/NasProperties`（`nexcompute.nas.*`）；`application.yml` 加 `spring.config.import` + `nexcompute.nas.*` 默认值；`SecurityConfig` permitAll 加 `/nas-allocation/register/**`。
- 全部管理员端方法加 `@Audited` + `requireAdmin()`；新增 `ErrorCode` 项（名额耗尽/令牌失效/状态非法等）。

**管理端前端（Vue 3 / AntD）**
- 新增 `views/admin/NasAllocationView.vue`（`a-tabs`：邀请管理 / 审批队列 / 已开通用户，含详情/批准/拒绝/改角色/重申上游/删除弹窗 + Toast）。
- 新增公开路由 `views/auth/NasRegisterView.vue`（表单 + 「待审批」成功页）。
- 新增 `api/nasAllocation.ts`；`router/index.ts` 加 `/admin/nas-allocation`（ADMIN）+ `/nas-register`（public）；`layouts/BasicLayout.vue` 菜单加「NAS分配」（ADMIN）。

**基础设施**
- Flyway `V32__nas_allocation.sql`：新建 `nas_invitation`、`nas_registration` 两表 + 索引 + 中文注释。SQL 全程不含 `${`（含注释/字符串，否则 Flyway 启动失败）。
- 根目录 `.env`（真实密钥，gitignore）+ `.env.example`（模板，入库）；docker-compose `backend` 改 `env_file: [.env]`。

**废弃**
- `D:\NAS管理` 独立 Python 门户废弃（数据全新开始，不写迁移脚本；密钥可重新生成）。
