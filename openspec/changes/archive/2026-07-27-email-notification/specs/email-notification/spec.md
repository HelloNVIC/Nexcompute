## ADDED Requirements

### Requirement: 邮件触发时机

系统 SHALL 在以下 8 类生命周期事件于业务事务成功路径上完成时，向受影响用户异步发送电子邮件提醒：用户注册、用户账户被禁用、用户账户已启用、用户被分配实例、用户实例分配被撤销、用户存储池迁移、用户镜像权限变化、用户容器权限变化。每类事件对应一个触发键，管理员可逐项开启或关闭全局发送。

#### Scenario: 事件成功后异步发送邮件
- **WHEN** 上述任一事件在业务事务成功提交后
- **THEN** 系统 SHALL 异步向受影响用户邮箱发送对应提醒邮件
- **AND** 邮件发送 SHALL NOT 阻断或回滚业务事务

#### Scenario: 管理员关闭某触发键全局开关
- **WHEN** 管理员在"系统信息"中将某触发键的全局开关置为关
- **THEN** 该类事件不再向任何用户发送邮件
- **AND** 其他触发键不受影响

#### Scenario: 仅成功路径发送
- **WHEN** 触发事件对应的业务操作抛出异常或失败
- **THEN** 系统 SHALL NOT 发送该事件邮件

### Requirement: 邮件正文含系统 Logo

每封提醒邮件正文顶部 SHALL 嵌入系统 Logo 图片，采用 CID 内联附件方式以确保跨邮件客户端稳定渲染。Logo 默认由后端资源提供（PNG 格式，由既有品牌 SVG 转出）；管理员可上传替换。

#### Scenario: 邮件正文嵌入 Logo
- **WHEN** 系统发送任一提醒邮件
- **THEN** 邮件 HTML 正文顶部 SHALL 包含系统 Logo（经 CID 内联附件 `<img src="cid:logo">`）
- **AND** Logo SHALL 以 PNG/JPG 格式提供以兼容主流邮件客户端

#### Scenario: 管理员上传替换 Logo
- **WHEN** 管理员在"系统信息"页上传新 Logo（PNG/JPG）
- **THEN** 系统 SHALL 存储该文件并在后续邮件中使用新 Logo
- **AND** 未上传时 SHALL 使用后端默认 PNG

#### Scenario: 拒绝不支持的 Logo 格式
- **WHEN** 管理员上传 SVG 或超限文件
- **THEN** 系统 SHALL 拒绝并提示原因

### Requirement: 邮件品牌名与落款可配置

邮件品牌名与落款 SHALL 存于 `system_config`，管理员可在"系统信息"页查看编辑；首启种子默认值。品牌名用于邮件品牌标题、落款与主题；落款支持多行文本。

#### Scenario: 管理员编辑品牌名与落款
- **WHEN** 管理员在"系统信息"页修改品牌名或落款并保存
- **THEN** 系统 SHALL 持久化至 `system_config`
- **AND** 后续邮件 SHALL 使用新品牌名与落款

#### Scenario: 非管理员不可编辑
- **WHEN** 非管理员请求修改品牌名或落款
- **THEN** 系统 SHALL 拒绝（仅管理员可设）

### Requirement: 邮件正文操作日志与文字提醒

每封提醒邮件正文 SHALL 含"操作日志"与"文字提醒"两段：操作日志结构化呈现本次操作的操作人、时间、操作类型、对象与变更详情（与审计同源）；文字提醒列出该事件的可执行提醒要点。

#### Scenario: 正文含操作日志
- **WHEN** 系统发送任一提醒邮件
- **THEN** 正文 SHALL 含操作日志段（操作人、时间、操作类型、对象、变更详情）

#### Scenario: 正文含文字提醒
- **WHEN** 系统发送任一提醒邮件
- **THEN** 正文 SHALL 含文字提醒段（该事件的可执行要点）

### Requirement: 用户邮件偏好 opt-out

学生与导师 SHALL 能逐项关闭除"用户注册""用户账户被禁用""用户账户已启用"之外的 5 类提醒邮件；"用户注册""用户账户被禁用""用户账户已启用"三类为强制提醒，用户不可关闭。默认所有可关闭项为开启（发送）。

#### Scenario: 学生或导师关闭某可关闭提醒
- **WHEN** 学生或导师在个人偏好中关闭某可关闭触发键
- **THEN** 该用户不再收到该类事件邮件
- **AND** 管理员全局开关与其他触发键不受影响

#### Scenario: 注册与禁用不可关闭
- **WHEN** 学生或导师查看偏好
- **THEN** "用户注册""用户账户被禁用""用户账户已启用"三项 SHALL 显示为不可关闭（强制）
- **AND** 即使用户偏好为关，这三类事件仍向该用户发送

#### Scenario: opt-out 不影响其他用户
- **WHEN** 用户 A 关闭某触发键
- **THEN** 仅用户 A 不再收到该类邮件
- **AND** 其他用户仍照其自身偏好与全局开关发送

### Requirement: 镜像权限变化通知范围

用户镜像权限变化时 SHALL 同时通知被共享的个人与该镜像所有原可见用户；收件人列表去重，同一用户仅发一封。

#### Scenario: 共享给个人同时通知原可见用户
- **WHEN** 镜像被共享给某用户或镜像可见性发生变更
- **THEN** 系统 SHALL 向被共享的个人发送邮件
- **AND** SHALL 向该镜像当前所有原可见用户发送邮件
- **AND** 收件人去重（同一用户仅一封）

### Requirement: SMTP 配置管理

SMTP 邮件配置 SHALL 以配置文件提供默认值，首次启动时种子入数据库 `system_config` 表；管理员可在"系统信息"页查看与编辑，编辑后持久化至数据库并刷新运行时邮件发送器。配置项含 FROM、PROTOCOL、SMTP_ADDR、SMTP_PORT、USER、PASSWD。

#### Scenario: 配置文件提供默认值并种子
- **WHEN** 系统首次启动且数据库无 SMTP 配置
- **THEN** 系统 SHALL 从配置文件读取默认值并种子入 `system_config`
- **AND** 默认值含 `FROM=cufel@cufe.edu.cn`、`PROTOCOL=smtps`、`SMTP_ADDR=smtp.exmail.qq.com`、`SMTP_PORT=465`、`USER=cufel@cufe.edu.cn`

#### Scenario: 管理员编辑 SMTP 配置后生效
- **WHEN** 管理员在"系统信息"页修改 SMTP 配置并保存
- **THEN** 系统 SHALL 将新值持久化至 `system_config`
- **AND** 后续邮件发送 SHALL 使用新配置（运行时发送器刷新）

#### Scenario: 非管理员不可编辑
- **WHEN** 非管理员请求修改 SMTP 配置
- **THEN** 系统 SHALL 拒绝（仅管理员可设）

#### Scenario: 密码不回显
- **WHEN** 系统返回当前 SMTP 配置给前端
- **THEN** PASSWD 字段 SHALL 脱敏（不回显明文）
- **AND** 仅在提交新值时接受明文写入

### Requirement: 异步发送与失败追踪

邮件发送 SHALL 异步执行，不阻塞业务事务；发送结果（触发键、收件人、主题、状态、错误、时间）SHALL 持久化至 `email_log` 表供管理员排查。

#### Scenario: 发送失败不阻断业务
- **WHEN** SMTP 发送失败
- **THEN** 系统 SHALL 记录失败至 `email_log`（status=FAILED + 错误原因）
- **AND** 业务事务 SHALL 不受影响

#### Scenario: 发送结果可查
- **WHEN** 管理员排查邮件送达
- **THEN** 系统 SHALL 提供 `email_log` 查询能力（按收件人/触发键/状态）
