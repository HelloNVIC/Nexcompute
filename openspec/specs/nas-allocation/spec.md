# nas-allocation Specification

## Purpose
TBD - created by archiving change nas-allocation. Update Purpose after archive.
## Requirements
### Requirement: NAS 邀请令牌管理

系统 SHALL 允许管理员创建不可猜测的邀请令牌，凭该令牌可注册 TrueNAS 用户；每个令牌有标签、最大使用次数、过期时间，可被撤销。名额消耗 SHALL 在单条条件 UPDATE 中原子完成，保证并发不超发。

#### Scenario: 管理员创建邀请
- **WHEN** 管理员提交 label、maxUses（≥1）、expireAt 创建邀请
- **THEN** 系统 SHALL 生成不可猜测的 token 并存 `nas_invitation`
- **AND** 系统 SHALL 返回完整注册链接 `{NAS_PORTAL_BASE_URL}/nas-register?token={token}`
- **AND** `created_by` SHALL 记录当前管理员 `app_user.id`

#### Scenario: 并发兑换不超发
- **WHEN** 多个注册请求并发兑换同一令牌且 `used_count` 已达 `max_uses`
- **THEN** 系统 SHALL 仅允许 `max_uses` 次成功，其余被拒
- **AND** 拒绝 SHALL 经单条条件 UPDATE（`used_count < max_uses AND revoked_at IS NULL AND expires_at > now`）判定

#### Scenario: 过期/撤销/名额耗尽各自精确报错
- **WHEN** 令牌已过期、已撤销或名额耗尽时被兑换
- **THEN** 系统 SHALL 各自返回精确错误码（过期/撤销/耗尽），不写注册行、不消耗名额

#### Scenario: 管理员撤销邀请
- **WHEN** 管理员撤销某邀请
- **THEN** 系统 SHALL 置 `revoked_at`，后续兑换被拒

### Requirement: 公开注册与密码暂存

系统 SHALL 提供公开注册端点（无需登录），凭有效邀请令牌接受 TrueNAS 用户注册申请。提交时 SHALL 原子消耗名额并以 AES-GCM 加密密码后写入 `PENDING` 行，不调用 TrueNAS。用户名在 `PENDING/APPROVED/FAILED` 状态下被占用，仅 `REJECTED` 释放。

#### Scenario: 公开提交注册申请
- **WHEN** 用户凭有效 token 提交 username（POSIX）、姓名、邮箱、手机号、密码（≥8 位含字母+数字）
- **THEN** 系统 SHALL 原子消耗一个邀请名额
- **AND** 系统 SHALL 以 AES-GCM 加密密码（密钥 `PASSWORD_ENC_KEY`，密文格式 `base64url(nonce12 + ct + tag)`）存 `password_enc`
- **AND** 系统 SHALL 写入 `PENDING` 行且不调用 TrueNAS
- **AND** 系统 SHALL 返回「待审批」结果

#### Scenario: 用户名占用
- **WHEN** 提交的 username 已存在于 `PENDING/APPROVED/FAILED` 状态的行
- **THEN** 系统 SHALL 拒绝注册，提示用户名已被占用
- **AND** 仅当占用行处于 `REJECTED` 时该用户名才释放可再注册

#### Scenario: 服务端强校验
- **WHEN** 提交字段不满足 POSIX 用户名 / 密码策略 / 必填
- **THEN** 系统 SHALL 服务端拒绝（非仅前端校验）

#### Scenario: pending 过期自动清理
- **WHEN** 某 `PENDING` 行提交时间超过 `NAS_PENDING_EXPIRE_DAYS`（默认 7）
- **THEN** 系统 SHALL 擦除其 `password_enc` 并置 `REJECTED`
- **AND** 清理 SHALL 由 `@Scheduled` 定期执行（默认每 6 小时）

### Requirement: 审批与 TrueNAS 开通

系统 SHALL 提供管理员审批队列，支持批准并开通、拒绝、删除记录、改角色、重申上游、批量刷新状态。批准时 SHALL 解密暂存密码经 TrueNAS REST 创建用户，成功后擦除密码并置 `APPROVED`；失败 SHALL 置 `FAILED` 保留密码可重试。

#### Scenario: 批准并开通
- **WHEN** 管理员对 `PENDING`/`FAILED` 行点批准并选 TrueNAS Web 后台角色（完全/只读/共享管理员/无，组 id 40/41/42）
- **THEN** 系统 SHALL 解密 `password_enc`
- **AND** 系统 SHALL 经 TrueNAS 幂等查重（`GET /user?username=&local=true`），存在则回填 id/uid，不存在则 `POST /user` 创建
- **AND** 成功后系统 SHALL 擦除 `password_enc`、置 `APPROVED`、回填 `truenas_user_id`/`truenas_uid`、记录 `reviewed_by`/`reviewed_at`
- **AND** 幂等：重复批准已 `APPROVED` 行 SHALL 无副作用返回

#### Scenario: 开通失败保留密码可重试
- **WHEN** TrueNAS 创建用户失败（权限不足/API 错误/网络不可达）
- **THEN** 系统 SHALL 置 `FAILED`、写 `provision_error`、**保留** `password_enc`
- **AND** 该行可再次批准重试

#### Scenario: 拒绝不退名额
- **WHEN** 管理员拒绝某 `PENDING` 行并填拒绝原因
- **THEN** 系统 SHALL 擦除 `password_enc`、置 `REJECTED`、记录原因与审核人
- **AND** 邀请名额 SHALL NOT 退还

#### Scenario: 改角色
- **WHEN** 管理员对已 `APPROVED` 行改选 Web 后台角色
- **THEN** 系统 SHALL 经 `PUT /user/id/{id}` 更新附加组：移除 40/41/42、加入新组、保留 `builtin_users`

#### Scenario: TrueNAS 用户已删自动翻转 NOT_FOUND
- **WHEN** 管理员查看某 `APPROVED` 行详情且 TrueNAS 上该用户已不存在（404）
- **THEN** 系统 SHALL 将本地状态翻转为 `NOT_FOUND`
- **AND** 系统 SHALL 允许「重申上游」用新密码重新创建用户

#### Scenario: 重申上游
- **WHEN** 管理员对 `NOT_FOUND` 行提交新密码与角色并点重申上游
- **THEN** 系统 SHALL 经 TrueNAS 幂等查重，不存在则创建，存在则回填，成功后置 `APPROVED`

#### Scenario: 批量刷新已开通用户状态
- **WHEN** 管理员触发刷新状态
- **THEN** 系统 SHALL 对所有 `APPROVED` 且有 `truenas_user_id` 的行复核 TrueNAS，404 者置 `NOT_FOUND`
- **AND** 网络错误者 SHALL 保持原状态不翻转

#### Scenario: 删除记录不删 TrueNAS 用户
- **WHEN** 管理员删除某注册行
- **THEN** 系统 SHALL 仅删除本地记录，不调用 TrueNAS 删用户

### Requirement: TrueNAS REST 集成

系统 SHALL 经 TrueNAS v2.0 REST API（`/api/v2.0`，Bearer API key 认证）管理用户。自签 TLS 场景下 SHALL 关闭证书校验。新用户 SHALL 默认开启 SMB；SSH 登录 SHALL 仅当 `TRUENAS_USER_HOME_PARENT` 配置时开启。

#### Scenario: 创建用户
- **WHEN** 系统开通新 TrueNAS 用户
- **THEN** 系统 SHALL `POST /user`，body 含 `username`/`full_name`/`password`/`email`/`smb:true`/`group_create:true`/`groups:[webuiGroupId]?`
- **AND** 当 `TRUENAS_USER_HOME_PARENT` 设置时 SHALL 设 `home`/`home_create`/`shell=/usr/bin/zsh`/`ssh_password_enabled=true`

#### Scenario: 幂等查重
- **WHEN** 开通前系统查重
- **THEN** 系统 SHALL `GET /user?username=&local=true&limit=1`
- **AND** 已存在则回填 id/uid 不重复创建

#### Scenario: 改组与详情
- **WHEN** 改角色或查详情
- **THEN** 系统 SHALL `PUT /user/id/{id}`（改组）或 `GET /user/id/{id}`（详情）

#### Scenario: 启动健康检查不阻断
- **WHEN** 应用启动完成
- **THEN** 系统 SHALL `GET /core/ping` 校验 TrueNAS 可达
- **AND** 失败 SHALL 仅记 WARN 日志，不阻断启动

### Requirement: 鉴权与统一 .env 配置

系统 SHALL 复用平台 ADMIN 鉴权管理 NAS 分配模块，所有管理员端接口要求 JWT + ADMIN 角色；公开注册接口 SHALL 免鉴权。TrueNAS 连接、AES 密钥、DB、JWT 等配置 SHALL 统一存于根 `.env`，`.env.example` 入库作模板，`.env` 被 gitignore。

#### Scenario: 管理员端鉴权
- **WHEN** 非管理员或未登录用户访问 `/admin/nas-*` 接口
- **THEN** 系统 SHALL 拒绝（403/401）
- **AND** 所有管理员端操作 SHALL 经 `@Audited` 记审计日志

#### Scenario: 公开注册免鉴权
- **WHEN** 未登录用户访问 `/nas-allocation/register/**`
- **THEN** 系统 SHALL 允许（permitAll）

#### Scenario: .env 统一配置
- **WHEN** 应用启动
- **THEN** 系统 SHALL 经 `spring.config.import: optional:file:.env[.properties]` 读取根 `.env`
- **AND** `TRUENAS_*`/`PASSWORD_ENC_KEY`/`NAS_*`/`DB_*`/`JWT_SECRET` 等 SHALL 作为配置项注入
- **AND** 无 `.env` 时 SHALL 回退 `${ENV:default}` 默认值正常启动

#### Scenario: 数据库并入 nexcompute 库
- **WHEN** Flyway 执行 `V32`
- **THEN** 系统 SHALL 在 `nexcompute` 库新建 `nas_invitation`、`nas_registration` 两表
- **AND** 表 SQL SHALL NOT 出现 `${`（含注释/字符串）

#### Scenario: docker-compose 读同一份 .env
- **WHEN** 容器编排启动 backend
- **THEN** docker-compose SHALL 经 `env_file: [.env]` 注入环境，与本地 Spring 直读同源
