# Design

## Context

见 proposal.md（Why 与线上重复数据实录）。相关现状：`autoRegister` 去重链为 SMBIOS UUID → (MAC AND 机器码) → 新建；实测 `processHeartbeat` 主流程在注册/复用返回后统一刷新 ipAddress/machineName/osInfo/gpuInfo/agentVersion/storageRoot（复用路径的可变属性更新无需在 reuseInstance 内重复实现，单测已断言）；受控端指纹在 Reporter 构造时一次性采集并缓存（CIM 一次性成本可接受，无每秒调用问题）；引用 physical_instance 的表共 10 张（见决策 4 清单，public_image_sync 为公共镜像库下线后的休眠表、无 JPA 实体）；`PhysicalInstanceService` 现无删除能力。

## Goals / Non-Goals

**Goals:**
- 机器指纹成为实例身份：SMBIOS 优先、machine_code 唯一可单独命中；同一硬件永远命中同一记录（编号、凭证不变）
- V37 一次性修正存量重复行（自动合并），并保证此后不再产生
- 管理员可安全删除实例（在线与占用拦截、级联清理、审计）
- 25H2 上 SMBIOS 主指纹恢复采集（CIM 优先 + wmic 兜底）
- 批量升级列表可辨识机器（IP 列）

**Non-Goals:**
- 不做字面的"machine_code 物理主键"改造（10 表 FK 重写、空值/重装变更主键不可接受，唯一索引达成同等语义）
- 不提供实例"合并"管理界面（存量合并由 V37 迁移一次性完成）
- 不处理无任何指纹的存量僵尸行自动识别（指纹全空的行无法自动归属，由管理员用删除功能手工清理）

## Decisions

### 1. 身份模型：指纹为身份，代理主键保留

- 匹配优先级：`smbios_uuid` 命中 → 复用；`machine_code` 非空且命中 → 复用（**去掉现"MAC AND 机器码都非空"条件**，MachineGuid 本身全局唯一，MAC 不再参与身份判定）；两者皆无 → 新建。
- 命中后统一走 `reuseInstance` 并补齐可变属性更新：`ipAddress`、`machineName`、`osInfo`、`gpuInfo`、`agentVersion`、`storageRoot`（当前缺失 ipAddress/gpuInfo/agentVersion/storageRoot，实现时补）。
- `id`（BIGSERIAL）继续作为全部 FK 锚点；`instance_number` 保持 UNIQUE + 管理员可改，语义降级为显示标签。
- 备选（拒绝）：machine_code 做物理主键——迁移需重写 10 张表 FK 与受控端凭证结构；machine_code 可为空（采集失败）且重装系统会变更，主键值不可变假设被破坏。

### 2. V37 迁移：索引 + 存量合并

```sql
-- 顺序：先数据修正，后建索引（否则索引建立即撞重复）
1) 按 smbios_uuid 分组合并（非空组）：
   keeper = 组内 last_heartbeat 最新（NULL 视为最旧）
   其余成员为 zombie
2) 按 machine_code 分组合并（非空组，同上）
   两轮合并的 zombie 集合取并集；若某 zombie 在另一组中是 keeper 则跳过（防交叉误删，记日志）
3) 引用重定向（zombie.id → keeper.id）：
   machine_allocation（撞 UNIQUE(instance_id,user_id) 时删 zombie 行）
   container / storage_pool / port_allocation / image_sync_task
   storage_migration.source_instance_id 与 target_instance_id
   public_image_sync（撞 UNIQUE(image_id,instance_id) 时删 zombie 行）
   agent_upgrade_task
4) 凭证与监控历史随 zombie 删除：agent_credential（instance_id UNIQUE，不可重定向）、monitoring_history
5) DELETE zombie 行
6) CREATE UNIQUE INDEX uq_instance_machine_code
     ON physical_instance(machine_code) WHERE machine_code IS NOT NULL AND machine_code <> '';
   CREATE UNIQUE INDEX uq_instance_smbios_uuid
     ON physical_instance(smbios_uuid) WHERE smbios_uuid IS NOT NULL AND smbios_uuid <> '';
```

- SQL 全程不得出现 `${`（含注释/字符串）。合并用纯 SQL（UPDATE ... FROM / DELETE USING），不用存储过程。
- 上线前在测试库（拷贝生产数据）演练并核对合并前后行数与引用计数。

### 3. 注册解析与实体（后端）

- `HeartbeatService.autoRegister`：匹配链改为 smbios → machine_code（单独）→ 新建；新建路径保持现指纹落库。
- 复用路径的可变属性更新由 `processHeartbeat` 主流程统一完成（实测确认，无需在 `reuseInstance` 内补字段）；单测断言复用后 IP/主机名/版本/存储根目录已刷新。
- 实体无结构变更（仅加索引），`ddl-auto=validate` 不受影响。

### 4. 删除实现（后端）

- `PhysicalInstanceService.delete(id)` + `DELETE /instances/{id}`，`@RequirePermission(module = "physical-instance", action = DELETE)` + 服务内管理员校验。
- 校验顺序：**在线判定最先**（`AgentCommandService.isAgentConnected(instanceNumber)`——在线机器删除后必然 4001 重注册复活）→ 占用检查。占用即拒（含计数，一次聚合报错）：

  | 拦截（存在即拒绝） | 表 |
  |---|---|
  | 用户分配 | machine_allocation |
  | 容器 | container |
  | 存储池 | storage_pool |
  | 端口分配 | port_allocation |
  | 迁移记录 | storage_migration（源或目标） |
  | 镜像同步任务 | image_sync_task |

  | 级联删除 | 表 |
  |---|---|
  | 鉴权凭证 | agent_credential |
  | 监控历史 | monitoring_history |
  | 公共镜像同步状态 | public_image_sync |
  | 升级任务记录 | agent_upgrade_task |

- 删除整体置于一个事务；记审计（action=INSTANCE_DELETE，目标=实例编号）。

### 5. SMBIOS 采集治本（受控端）

- `sysinfo.collectSmbiosUUID`：优先 PowerShell CIM——`powershell -NoProfile -Command "(Get-CimInstance Win32_ComputerSystemProduct).UUID"`（HideWindow，5s 超时不变）；失败/空回退现 wmic 路径。解析逻辑（跳过表头、全 0 判缺）复用。
- 采集时点不变（Reporter 构造一次缓存）；采集失败行为与现状一致（指纹空、按回退链降级）。

### 6. 前端

- `AgentUpgradeView.vue`"批量/单独升级"表格在"机器名"后加 `IP` 列（`data-index="ipAddress"`，数据已在 `instanceApi.list()` 返回中）。
- 实例管理视图：操作列加"删除"（仅管理员可见）→ `Modal.confirm`（列明在线/占用会被拒绝）→ 调删除接口；拦截错误信息（含计数）由拦截器 toast 原文展示。

## Risks / Trade-offs

- [V37 含存量数据修正，回滚困难] → 上线前测试库演练并核对行数/引用；迁移本身幂等语义清晰（合并后无重复即无操作）。
- [交叉指纹合并误删（A 与 B 同 smbios、B 与 C 同 machine_code）] → 决策 2 的"zombie 在另一组为 keeper 则跳过"防御 + 演练核对；极端残余重复由管理员手工删除兜底。
- [reuseInstance 补字段更新影响既有心跳更新路径] → 单测覆盖复用路径的字段断言。
- [CIM 在 PowerShell 受限环境不可用] → wmic 兜底 + 两者皆空时与现状一致（回退链降级），不阻断注册。
- [在线判定与实际断连存在秒级窗口] → 可接受；误删在线实例会被 4001 重注册机制自然恢复为新行，不产生数据损坏。

## Migration Plan

受控端（SMBIOS 采集）经 OTA 独立生效，与后端 V37 无顺序依赖；建议先发后端 V37（存量合并 + 唯一索引止血——即便旧版受控端，machine_code 命中已可去重），再 OTA 受控端（恢复 25H2 主指纹）。前端随管理端同期发布。

## Open Questions

（无——关键决策均已定；合并细节如"UNIQUE 撞行时删 zombie 行保留 keeper 行"为实现期可调细节）
