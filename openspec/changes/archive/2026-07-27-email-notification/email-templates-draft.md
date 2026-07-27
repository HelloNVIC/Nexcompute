# 邮件文案草案（email-notification）

> 实现 `EmailTemplateService` 时按此草案渲染;验收时调。所有正文经统一 HTML 外壳(Logo + 品牌标题 + 称呼 + 正文 + 操作日志 + 文字提醒 + 落款),下面每条只列**主题**与**正文片段**及**操作日志字段**/**提醒要点**。
> 占位符统一 `${var}` 风格;`${brandName}`、`${signature}`、`${operatorName}` 等为公共变量。

## 通用 HTML 外壳

```html
<img src="cid:logo" alt="${brandName}" height="40" />
<div class="brand">${brandName}</div>

<p>{greeting}，您好：</p>

<div class="body">
  {正文片段}
</div>

<div class="op-log">
  <strong>操作日志</strong>
  <table>
    <tr><td>操作人</td><td>${operatorName}</td></tr>
    <tr><td>操作时间</td><td>${time}</td></tr>
    <tr><td>操作类型</td><td>${actionLabel}</td></tr>
    <tr><td>操作对象</td><td>${targetLabel}</td></tr>
    <tr><td>变更详情</td><td>${changeDetails}</td></tr>
  </table>
</div>

<div class="remind">
  <strong>提醒</strong>
  <ul>{提醒要点}</ul>
</div>

<div class="signature">${signature}</div>
```

- **Logo**：经 CID 内联附件 `cid:logo`(D9);默认 classpath PNG,管理员可上传替换。
- **`${brandName}`**：`system_config` `email.brand.name`,默认"合算 Nexcompute",管理员可改(D10)。
- **`${signature}`**：`system_config` `email.signature`,默认落款(多行),管理员可改(D10)。
- **称呼 `{greeting}`**：按收件人角色派生--`STUDENT -> "${realName} 同学"`;`MENTOR/ADMIN -> "${realName} 老师"`。
- **操作日志(D11)**：与审计同源,`${actionLabel}`/`${targetLabel}`/`${changeDetails}` 按触发键派生(见下)。
- **multipart**：HTML 为主,附 plain-text fallback。

---

## 1. USER_REGISTERED -- 用户注册

- **收件人**：新注册用户本人
- **ctx**：`realName`、`username`、`groupName`、`mentorName`(如有)、`operatorName`(系统/自助)、`time`
- **主题**：欢迎注册 ${brandName}
- **正文片段**：
  > 您的账号已注册成功,欢迎使用 ${brandName}。
  >
  > 您已被加入课题组「**${groupName}**」,后续可登录平台使用计算实例、存储池与镜像等资源。请妥善保管账号,首次登录后建议完善个人信息。
- **操作日志字段**：操作类型=用户注册；操作对象=${realName}(${username})；变更详情=加入课题组 ${groupName}
- **提醒要点**：
  - 首次登录后请及时完善个人信息（邮箱/手机号）。
  - 请妥善保管账号密码,勿与他人共享。

---

## 2. USER_DISABLED -- 用户账户被禁用

- **收件人**：被禁用用户本人
- **ctx**：`realName`、`username`、`operatorName`、`reason`(可空)、`time`
- **主题**：您的 ${brandName} 账户已被禁用
- **正文片段**：
  > 您的账号已被管理员禁用,即日起将无法登录 ${brandName}。
  >
  > 如需了解原因或申请恢复,请联系系统管理员。
- **操作日志字段**：操作类型=禁用账户；操作对象=${realName}(${username})；变更详情=${reason 存在时："原因 ${reason}",否则留空}
- **提醒要点**：
  - 账号已无法登录,相关进行中的任务请提前与课题组沟通处理。
  - 如需恢复,请联系系统管理员。

---

## 2b. USER_ENABLED -- 用户账户已启用

- **收件人**：被启用用户本人
- **ctx**：`realName`、`username`、`operatorName`、`time`
- **主题**：您的 ${brandName} 账户已启用
- **正文片段**：
  > 您的账号已被管理员启用,即日起可登录 ${brandName}。
  >
  > 如登录遇到问题,请联系系统管理员。
- **操作日志字段**：操作类型=启用账户；操作对象=${realName}(${username})；变更详情=（空）
- **提醒要点**：
  - 请妥善保管账号密码,勿与他人共享。
  - 首次登录后建议完善个人信息（邮箱/手机号）。
  - 如登录异常,请联系系统管理员。

---

## 3. INSTANCE_ALLOCATED -- 用户被分配实例

- **收件人**：被分配实例的用户
- **ctx**：`realName`、`instanceName`、`instanceNumber`、`operatorName`、`time`
- **主题**：实例「${instanceName}」已分配给您
- **正文片段**：
  > 一台物理实例已分配给您使用。
  >
  > 实例「**${instanceName}**」(编号 ${instanceNumber})现已可用,您可登录平台在"物理实例"中查看其状态并部署容器。
- **操作日志字段**：操作类型=分配实例；操作对象=${instanceName}(编号 ${instanceNumber})；变更详情=分配给 ${realName}
- **提醒要点**：
  - 请勿在实例上存放未备份的重要数据,平台不保证单实例数据持久。
  - 使用完毕请及时释放资源,避免占用。

---

## 4. INSTANCE_DEALLOCATED -- 用户实例分配被撤销

- **收件人**：被撤销分配的用户
- **ctx**：`realName`、`instanceName`、`instanceNumber`、`operatorName`、`time`
- **主题**：实例「${instanceName}」的分配已撤销
- **正文片段**：
  > 您此前使用的物理实例分配已被撤销。
  >
  > 实例「**${instanceName}**」(编号 ${instanceNumber})已不再分配给您。请提前备份该实例上的重要数据;如需继续使用,请联系系统管理员重新申请。
- **操作日志字段**：操作类型=撤销实例分配；操作对象=${instanceName}(编号 ${instanceNumber})；变更详情=从 ${realName} 撤销
- **提醒要点**：
  - 请立即备份实例上的重要数据,撤销后将无法访问。
  - 如需继续使用,请联系系统管理员重新申请。

---

## 5. STORAGE_POOL_MIGRATED -- 用户存储池迁移

- **收件人**：存储池所有者
- **ctx**：`realName`、`poolName`、`sourceHost`、`targetHost`、`operatorName`、`time`、`status`(迁移中/已确认)
- **主题**：存储池「${poolName}」迁移通知
- **正文片段**：
  > 您的存储池已发起迁移。
  >
  > 存储池「**${poolName}**」正由「${sourceHost}」迁移至「${targetHost}」。迁移期间该存储池暂不可写,完成后将恢复访问。请在迁移完成后确认数据完整性。
- **操作日志字段**：操作类型=存储池迁移；操作对象=${poolName}；变更详情=${sourceHost} -> ${targetHost}(${status})
- **提醒要点**：
  - 迁移期间存储池暂不可写,请避免写入操作。
  - 迁移完成后请及时核对数据完整性。

---

## 6. IMAGE_PERMISSION_CHANGED -- 用户镜像权限变化

- **收件人**：被共享的个人 **∪** 该镜像所有原可见用户(D7,去重)
- **ctx**：`realName`、`imageName`、`relation`(SHARED_TO | ALREADY_VISIBLE)、`changeSummary`、`operatorName`、`sharerName`、`time`
- **主题**：镜像「${imageName}」权限变更通知
- **正文片段**(按 `relation` 分两种措辞):

  **relation=SHARED_TO(新获得权限)**：
  > 镜像「**${imageName}**」已由「${sharerName}」共享给您,您现已可使用该镜像。您可登录平台在"镜像"中查看并基于该镜像创建容器。

  **relation=ALREADY_VISIBLE(原可见用户,权限变更)**：
  > 您可见的镜像「**${imageName}**」的权限发生了变更。变更：${changeSummary}(由「${operatorName}」操作)。如该变更影响您的使用,请及时关注。

- **操作日志字段**：操作类型=镜像权限变更；操作对象=${imageName}；变更详情=${changeSummary};操作发起人=${sharerName}
- **提醒要点**：
  - SHARED_TO:基于该镜像创建的容器数据请自行备份。
  - ALREADY_VISIBLE:若该变更撤销了您的访问,请及时联系共享人或管理员。

---

## 7. CONTAINER_PERMISSION_CHANGED -- 用户容器权限变化

- **收件人**：被共享/取消共享的用户
- **ctx**：`realName`、`containerName`、`changeType`(SHARED | UNSHARED)、`sharerName`、`expiresAt`(可空)、`operatorName`、`time`
- **主题**：容器「${containerName}」权限变更通知
- **正文片段**(按 `changeType` 分两种措辞):

  **changeType=SHARED**：
  > 容器「**${containerName}**」已由「${sharerName}」共享给您。您可登录平台在"容器"中查看并进入该容器。${expiresAt 存在时追加："本次共享将于 ${expiresAt} 到期。"}

  **changeType=UNSHARED**：
  > 您此前被共享的容器「**${containerName}**」已被取消共享。您将不再能访问该容器,请提前备份其中的重要数据。

- **操作日志字段**：操作类型=容器共享/取消共享(${changeType})；操作对象=${containerName}；变更详情=共享人 ${sharerName}${expiresAt 存在时追加:",到期 ${expiresAt}"}
- **提醒要点**：
  - SHARED:共享容器内请勿存放隐私数据,所有者可随时取消共享。
  - UNSHARED:请立即备份容器内重要数据,取消后无法访问。

---

## 占位符与派生规则小结

| 占位符 | 来源 / 派生 |
|---|---|
| `${brandName}` | `system_config` `email.brand.name`,默认"合算 Nexcompute"(D10) |
| `${signature}` | `system_config` `email.signature`,默认落款(多行)(D10) |
| `${realName}` / `${username}` | 收件人 `User` |
| `greeting` | 角色派生:STUDENT->同学,MENTOR/ADMIN->老师 |
| `${operatorName}` | 操作人 realName(业务线程内同步取快照,仿 `AuditContextResolver`) |
| `${sharerName}` | 共享操作发起人 realName |
| `${time}` | 调用 `sendAt` 时的时间(异步线程无请求上下文,调用方传入或落库时戳) |
| `${actionLabel}` | 触发键的中文 label(枚举常量,如 USER_DISABLED->"禁用账户") |
| `${targetLabel}` | 操作对象中文标识(如"用户 张三(zhangsan)"/"实例 host01(编号 7)") |
| `${changeDetails}` | 变更详情,按触发键 ctx 派生(键值或 before/after) |
| `${groupName}` / `${mentorName}` | 注册 ctx |
| `${instanceName}` / `${instanceNumber}` | `ResourceAllocationService` ctx |
| `${poolName}` / `${sourceHost}` / `${targetHost}` / `${status}` | `StoragePoolService.migrate` ctx |
| `${imageName}` / `${relation}` / `${changeSummary}` | `ImageService` ctx;`relation` 按收件人是否新增被共享者判定 |
| `${containerName}` / `${changeType}` / `${expiresAt}` | `ContainerService.share`/`unshare` ctx |

## 实现注意(给 EmailTemplateService)

- **异步上下文**:`operatorName`/`sharerName` 等需在调用 `sendAt` 的业务线程内同步取(异步线程无 `SecurityContext`),仿 `AuditContextResolver.resolve()` 模式--ctx 在调用点整包组装传入。
- **收件人去重**(D7):镜像权限变化先算 `被共享个人 ∪ 原可见用户` 集合,逐个 `sendAt`,同一用户只发一封;`relation` 按该用户是否为新增被共享者派生。
- **品牌/落款/Logo 动态读**:`EmailService` 发送前读 `system_config` 的 `email.brand.name`/`email.signature`/`email.brand.logo_filename`(缓存,配置变更刷新);Logo 文件优先 `${storage.root}/email/` 下已上传,无则 classpath 默认 PNG。
- **plain-text fallback**:HTML 为主、附简化纯文本。
- **文案调校**:本草案为初稿,验收阶段按实际语感调整;模板集中在 `EmailTemplateService` 便于改文案不触动业务。
