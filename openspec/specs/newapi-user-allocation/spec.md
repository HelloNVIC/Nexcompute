# newapi-user-allocation Specification

## Purpose
TBD - created by archiving change add-newapi-user-allocation. Update Purpose after archive.
## Requirements
### Requirement: NewAPI 邀请令牌管理

系统 SHALL 允许管理员创建不可猜测的邀请令牌，凭该令牌可注册 NewAPI 用户；每个令牌有标签、最大使用次数、过期时间，可被撤销。名额消耗 SHALL 在单条条件 UPDATE 中原子完成，保证并发不超发。

#### Scenario: 管理员创建邀请
- **WHEN** 管理员提交 label、maxUses（≥1）、expireAt 创建邀请
- **THEN** 系统 SHALL 生成不可猜测的 token 并存 `newapi_invitation`
- **AND** 系统 SHALL 返回完整注册链接 `{NEWAPI_PORTAL_BASE_URL}/newapi-register?token={token}`
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

系统 SHALL 提供公开注册端点（无需登录），凭有效邀请令牌接受 NewAPI 用户注册申请。提交时 SHALL 原子消耗名额并以 AES-GCM 加密密码后写入 `PENDING` 行，不调用 NewAPI。用户名在 `PENDING/APPROVED/FAILED` 状态下被占用，仅 `REJECTED` 释放。

#### Scenario: 公开提交注册申请
- **WHEN** 用户凭有效 token 提交 username、display_name、邮箱、手机号、密码（≥8 位含字母+数字）
- **THEN** 系统 SHALL 原子消耗一个邀请名额
- **AND** 系统 SHALL 以 AES-GCM 加密密码（密钥 `PASSWORD_ENC_KEY`，密文格式 `base64url(nonce12 + ct + tag)`）存 `password_enc`
- **AND** 系统 SHALL 写入 `PENDING` 行且不调用 NewAPI
- **AND** 系统 SHALL 返回「待审批」结果

#### Scenario: 用户名占用
- **WHEN** 提交的 username 已存在于本地 `PENDING/APPROVED/FAILED` 状态的行
- **THEN** 系统 SHALL 拒绝注册，提示用户名已被占用
- **AND** 仅当占用行处于 `REJECTED` 时该用户名才释放可再注册

#### Scenario: NewAPI 用户名查重
- **WHEN** 用户名输入后实时查询可用性
- **THEN** 系统 SHALL 调 NewAPI `GET /api/user/search?keyword=<username>` 查重
- **AND** NewAPI 已存在 SHALL 报 `NEWAPI_TAKEN`
- **AND** NewAPI 不可达 SHALL 返回 `UNREACHABLE` 不阻断注册（前端提示用户）

#### Scenario: 服务端强校验
- **WHEN** 提交字段不满足 NewAPI 用户名规则 / 密码策略 / 必填
- **THEN** 系统 SHALL 服务端拒绝（非仅前端校验）

#### Scenario: pending 过期自动清理
- **WHEN** 某 `PENDING` 行提交时间超过 `NEWAPI_PENDING_EXPIRE_DAYS`（默认 7）
- **THEN** 系统 SHALL 擦除其 `password_enc` 并置 `REJECTED`
- **AND** 清理 SHALL 由 `@Scheduled` 定期执行（默认每 6 小时）

### Requirement: 审批与 NewAPI 开通

系统 SHALL 提供管理员审批队列，支持批准并开通、拒绝、删除记录、改分组、重申上游、批量刷新状态。批准时 SHALL 解密暂存密码经 NewAPI REST 创建用户，成功后擦除密码并置 `APPROVED`；失败 SHALL 置 `FAILED` 保留密码可重试。

#### Scenario: 批准并开通
- **WHEN** 管理员对 `PENDING`/`FAILED` 行点批准并选 NewAPI `group` 分组
- **THEN** 系统 SHALL 解密 `password_enc`
- **AND** 系统 SHALL 经 NewAPI 幂等查重（`GET /api/user/search?keyword=<username>`），存在则回填 id，不存在则 `POST /api/user/` 创建
- **AND** 因 `POST /api/user/` 响应无 id，系统 SHALL 建后立即 `search` 回查拿 `newapi_user_id` 回填
- **AND** 成功后系统 SHALL 擦除 `password_enc`、置 `APPROVED`、回填 `newapi_user_id`、记录 `reviewed_by`/`reviewed_at`
- **AND** 幂等：重复批准已 `APPROVED` 行 SHALL 无副作用返回

#### Scenario: 开通失败保留密码可重试
- **WHEN** NewAPI 创建用户失败（令牌权限不足/API 错误/网络不可达）
- **THEN** 系统 SHALL 置 `FAILED`、写 `provision_error`、**保留** `password_enc`
- **AND** 该行可再次批准重试

#### Scenario: 拒绝不退名额
- **WHEN** 管理员拒绝某 `PENDING` 行并填拒绝原因
- **THEN** 系统 SHALL 擦除 `password_enc`、置 `REJECTED`、记录原因与审核人
- **AND** 邀请名额 SHALL NOT 退还

#### Scenario: 改分组
- **WHEN** 管理员对已 `APPROVED` 行改选 NewAPI `group`
- **THEN** 系统 SHALL 经 `PUT /api/user/` 更新（body 含 id+username+group）
- **AND** 系统 SHALL NOT 改 quota（PUT 不生效）

#### Scenario: NewAPI 用户已删自动翻转 NOT_FOUND
- **WHEN** 管理员查看某 `APPROVED` 行详情且 NewAPI 上该用户已不存在（404）
- **THEN** 系统 SHALL 将本地状态翻转为 `NOT_FOUND`
- **AND** 系统 SHALL 允许「重申上游」用新密码重新创建用户

#### Scenario: 重申上游
- **WHEN** 管理员对 `NOT_FOUND` 行提交新密码与 group 并点重申上游
- **THEN** 系统 SHALL 经 NewAPI 幂等查重，不存在则创建，存在则回填，成功后置 `APPROVED`

#### Scenario: 批量刷新已开通用户状态
- **WHEN** 管理员触发刷新状态
- **THEN** 系统 SHALL 对所有 `APPROVED` 且有 `newapi_user_id` 的行复核 NewAPI，404 者置 `NOT_FOUND`
- **AND** 网络错误者 SHALL 保持原状态不翻转

#### Scenario: 删除记录不删 NewAPI 用户
- **WHEN** 管理员删除某注册行
- **THEN** 系统 SHALL 仅删除本地记录，不调用 NewAPI 删用户

### Requirement: NewAPI REST 集成

系统 SHALL 经 NewAPI v1.0.0-rc.22 用户管理 REST API（`/api/user/`，`Authorization: Bearer <系统访问令牌>` + `New-Api-User: <uid>` 头）管理用户。新用户额度由 NewAPI 全局 `QuotaForNewUser` 默认值授予（非本系统职责）。

#### Scenario: 创建用户
- **WHEN** 系统开通新 NewAPI 用户
- **THEN** 系统 SHALL `POST /api/user/`，body 含 `username`/`password`/`display_name`/`group`
- **AND** 系统 SHALL NOT 传 `quota`（被 NewAPI 忽略，额度走 `QuotaForNewUser`）
- **AND** 建后 SHALL `GET /api/user/search?keyword=<username>` 回查拿 id

#### Scenario: 幂等查重
- **WHEN** 开通前系统查重
- **THEN** 系统 SHALL `GET /api/user/search?keyword=<username>`
- **AND** 已存在则回填 id 不重复创建

#### Scenario: 改分组与详情
- **WHEN** 改分组或查详情
- **THEN** 系统 SHALL `PUT /api/user/`（body 含 id+username+group）或 `GET /api/user/{id}`（详情）

#### Scenario: 启动健康检查不阻断
- **WHEN** 应用启动完成
- **THEN** 系统 SHALL `GET /api/status` 校验 NewAPI 可达
- **AND** 失败 SHALL 仅记 WARN 日志，不阻断启动

#### Scenario: 严禁调用令牌轮换端点
- **WHEN** 系统与 NewAPI 交互
- **THEN** 系统 SHALL NOT 调用 `GET /api/user/token`（会轮换系统访问令牌，旧令牌立即失效）
- **AND** 客户端方法集 SHALL 显式排除该端点

#### Scenario: 额度运维前置
- **WHEN** 部署 Token分配功能
- **THEN** 运维 SHALL 经 `PUT /api/option/ {"key":"QuotaForNewUser","value":"<大值>"}` 设新用户默认额度
- **AND** 运维 SHALL 关闭 NewUI 公开自注册（`register_enabled`/`password_register_enabled`）防白嫖
- **AND** 本系统 SHALL NOT 包含 per-user 额度授予代码

### Requirement: 鉴权与统一 docker-compose 配置注入

系统 SHALL 复用平台 ADMIN 鉴权管理 Token分配模块，所有管理员端接口要求 JWT + ADMIN 角色；公开注册接口 SHALL 免鉴权。NewAPI 连接、AES 密钥、DB、JWT 等配置 SHALL 经 `docker-compose.yml`/`docker-compose.prod.yml` 的 `environment:` 内联注入（真实文件 gitignored，模板 `docker-compose.example.yml`/`docker-compose.prod.example.yml` 入库）。**`spring.config.import` 已移除、`.env` 不再被 Spring 读取**。

#### Scenario: 管理员端鉴权
- **WHEN** 非管理员或未登录用户访问 `/admin/newapi-*` 接口
- **THEN** 系统 SHALL 拒绝（403/401）
- **AND** 所有管理员端操作 SHALL 经 `@Audited` 记审计日志

#### Scenario: 公开注册免鉴权
- **WHEN** 未登录用户访问 `/newapi-allocation/register/**`
- **THEN** 系统 SHALL 允许（permitAll）

#### Scenario: docker-compose environment 注入配置
- **WHEN** 应用启动
- **THEN** 系统 SHALL 经 docker-compose `environment:` 块读取 `NEWAPI_*`/`PASSWORD_ENC_KEY`/`DB_*`/`JWT_SECRET` 等注入为环境变量
- **AND** `application.yml` SHALL 以 `${ENV:default}` 占位绑定，无注入时回退默认值
- **AND** 系统 SHALL NOT 读取 `.env`（`spring.config.import` 已移除、`.env.example` 已删）
- **AND** bootRun SHALL 经 shell 环境变量或 `${ENV:default}` 默认值启动

#### Scenario: 数据库并入 nexcompute 库
- **WHEN** Flyway 执行 `V33`
- **THEN** 系统 SHALL 在 `nexcompute` 库新建 `newapi_invitation`、`newapi_registration` 两表
- **AND** 表 SQL SHALL NOT 出现 `${`（含注释/字符串）

#### Scenario: docker-compose 模板与真实文件分离
- **WHEN** 容器编排启动 backend
- **THEN** 真实 `docker-compose.yml`/`.prod.yml` SHALL 内联真实密钥于 `environment:`（gitignored）
- **AND** 模板 `docker-compose.example.yml`/`.prod.example.yml` SHALL 入库含占位值
- **AND** 新增 `NEWAPI_*` 变量 SHALL 同时进模板与真实 compose 的 `environment:` 块

