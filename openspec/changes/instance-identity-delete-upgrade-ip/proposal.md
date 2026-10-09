# Proposal

## Why

线上物理实例重复严重：同一台物理机在库中有多条记录（如 DESKTOP-N0E4ZD1 = 实例 17/19/25/10，HN8BU87 = 23/6 等），且系统没有任何删除实例的能力，僵尸行只能躺在库里。重复的三个根因：① `wmic` 在 Windows 11 25H2 起被移除，受控端 SMBIOS 主指纹采集静默失败（机队 osInfo 全为 25H2）；② 注册去重回退条件要求 MAC 与机器码**都**非空，MAC 缺失即整体放弃匹配；③ 指纹机制上线前的历史行指纹全空，活机器永远匹配不上。另外管理员在"受控端升级"批量升级时需要辨认机器，但列表没有 IP 列。

## What Changes

- **实例身份模型改造**：机器指纹成为实例身份——SMBIOS UUID 优先匹配、machine_code（MachineGuid）加部分唯一索引单独可命中；物理机编号降级为管理员可改的显示标签；保留代理主键 `id` 作为全部外键锚点（不做字面的"机器码做物理主键"，避免 10 张表 FK 重写与空值/重装变更主键的灾难）。指纹命中即复用既有记录（编号+凭证），IP/主机名/OS/GPU/版本等作为可变属性在复用时更新。
- **V37 存量数据修正**：对 smbios_uuid、machine_code 分别建部分唯一索引（排除 NULL/空串）；存量重复行自动合并——同指纹分组保留"最新心跳"一条，将其余行的关联引用（分配/容器/存储池/端口/迁移/公共镜像同步/镜像同步任务/升级任务记录）重定向到保留行后删除僵尸行（凭证与监控历史随僵尸行删除）。
- **注册解析简化**：去掉"MAC 与机器码都非空"的回退条件，machine_code 单独命中即复用。
- **管理员删除物理实例**：`DELETE /instances/{id}`；在线实例（WS 已连接）直接拒绝；存在用户分配、容器、存储池、端口分配、迁移记录、镜像同步任务任一即拒绝并提示各类计数；级联清理凭证/监控历史/环境同步状态；记审计日志；前端实例列表增加删除入口与确认。
- **SMBIOS 采集治本（受控端）**：`collectSmbiosUUID` 改为 PowerShell CIM（`Get-CimInstance Win32_ComputerSystemProduct`）优先、`wmic` 兜底，修复 25H2 上主指纹失效。
- **批量升级列表显示 IP**：受控端升级"批量/单独升级"实例表格增加 IP 列（`ipAddress` 数据已随实例列表返回，纯前端加列）。

## Capabilities

### New Capabilities

（无——四项均落在既有能力边界内）

### Modified Capabilities

- `physical-instance`：
  - 修改「物理实例注册与编号」：身份由机器指纹决定（SMBIOS 优先/机器码唯一），编号降级为显示标签；指纹命中复用并更新可变属性（IP 变化不新建）；
  - 修改「硬件指纹来源与稳定性」：SMBIOS 采集经 CIM 优先、wmic 兜底；机器码作为单独可命中的身份回退（不再要求 MAC 同时匹配）；
  - 新增「物理实例删除」：在线与六类占用拒绝（含计数提示）、级联清理、审计。
- `agent-ota`：
  - 修改「受控端远程升级」：批量/单独升级实例列表展示各机器 IP 地址。

## Impact

- **management-backend**：`HeartbeatService`（autoRegister 解析与 reuseInstance 补充字段更新）；`PhysicalInstanceService`/`PhysicalInstanceController`（删除端点与校验）；Flyway **V37**（部分唯一索引 + 存量合并数据修正）；审计。
- **controlled-agent（Go）**：`internal/sysinfo/sysinfo.go`（collectSmbiosUUID 改 CIM 优先 + wmic 兜底）。
- **management-frontend**：`views/admin/AgentUpgradeView.vue`（IP 列）；实例管理视图（删除按钮 + 确认弹窗 + 拦截原因展示）。
- **数据库**：V37 含存量数据修正（重定向引用后删除僵尸行），上线前应在测试库演练；不可简单回滚。
