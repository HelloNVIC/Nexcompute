# Tasks

## 1. 后端：注册身份解析与复用更新

- [x] 1.1 `HeartbeatService.autoRegister`：匹配链改为 smbios_uuid → machine_code 单独命中（移除"MAC AND 机器码都非空"条件）→ 新建。Mockito 单测覆盖：SMBIOS 命中复用、机器码单独命中复用（MAC 为空）、MAC 匹配但机器码不同不误判、两者皆无新建——对应测试类经 Gradle 全绿
- [x] 1.2 `reuseInstance` 补齐可变属性更新（ipAddress/gpuInfo/agentVersion/storageRoot，非空才覆盖），单测断言复用后字段已刷新——Gradle 全绿

## 2. 后端：V37 迁移（索引 + 存量合并）

- [x] 2.1 编写 `V37__instance_identity_dedup.sql`：按 design #2 顺序（smbios 合并 → machine_code 合并 → 引用重定向 → 凭证/监控历史随删 → 删 zombie → 两个部分唯一索引），全文无 `${`，`COMMENT ON` 说明各段用途
- [x] 2.2 测试库演练：以生产数据拷贝执行迁移，核对合并前后（实例行数、各引用表行数、keeper 唯一性）并记录核对结果；确认 `ddl-auto=validate` 启动无报错（实际于生产库完成：迁移前表列核对 14 项命中，25→16 实例，死锁教训见 design 风险）
- [x] 2.3 存量合并正确性验证脚本/查询：迁移后无任何"同 smbios_uuid 多行"或"同 machine_code 多行"（排除空值）；被合并 zombie 的原编号不再占用（生产验证：重复组 0/0，僵尸编号已释放，两唯一索引在位）

## 3. 后端：管理员删除实例

- [x] 3.1 `PhysicalInstanceService.delete`：事务内——在线拒绝（isAgentConnected）→ 六类占用检查（machine_allocation/container/storage_pool/port_allocation/storage_migration/image_sync_task，聚合计数报错）→ 级联删（agent_credential/monitoring_history/public_image_sync/agent_upgrade_task）→ 删实例 → 审计。Mockito 单测覆盖：在线拒绝、每类占用拒绝（错误含计数）、成功删除级联清理——Gradle 全绿
- [x] 3.2 `PhysicalInstanceController`：`DELETE /instances/{id}`，`@RequirePermission(module="physical-instance", action=DELETE)` + 管理员校验——权限矩阵低权限账号手动验证 403
- [ ] 3.3 手动/联调验证：对测试库僵尸行执行删除成功；在线实例删除被拒并提示；已分配实例删除被拒并提示计数

## 4. 受控端：SMBIOS 采集治本

- [x] 4.1 `sysinfo.collectSmbiosUUID`：CIM 优先（PowerShell `Get-CimInstance Win32_ComputerSystemProduct`，HideWindow + 5s 超时）、失败/空回退 wmic；解析与全 0 判缺复用。单测：模拟输出解析（表头跳过/全 0/正常 UUID）——`go test ./internal/sysinfo/` 通过
- [ ] 4.2 真机验证：在 Win11 25H2 机器运行受控端，确认心跳携带非空 smbiosUUID（管理端实例列表/日志核对）
- [x] 4.3 `go test ./...` 全绿，`build.ps1 -Target Agent` 构建通过

## 5. 前端

- [x] 5.1 `AgentUpgradeView.vue`："批量/单独升级"表格"机器名"后加 IP 列（`data-index="ipAddress"`）——`npm run type-check` 通过
- [x] 5.2 实例管理视图：操作列加"删除"按钮（仅管理员）+ `Modal.confirm`（说明在线/占用会被拒绝）+ 调用删除接口——type-check 通过
- [ ] 5.3 手动联调：删除在线实例被拒提示、删除占用实例提示计数、删除僵尸实例成功并从列表消失；批量升级列表显示各机器 IP

## 6. 收尾

- [x] 6.1 三端构建全绿：`go test ./...`、Gradle `test`、`npm run build`
- [x] 6.2 README 同步：实例身份/去重/删除说明、V37
- [ ] 6.3 生产部署与存量清理验证：V37 应用后实例列表无同机重复；受控端 OTA 升级后 25H2 机器 smbiosUUID 非空

## 7. 追加（用户复核反馈）：删除强制选项与失败任务清理

- [x] 7.1 删除实例对镜像同步任务改为可强制：`DELETE /instances/{id}?force=true` 跳过该项检查并级联删任务行（能删到的实例必离线、任务必终态，强删无损）；非 force 仍拦截并提示"可强制清除"；前端弹窗文案明示同步任务一并删除、确认即 force——单测覆盖两路径
- [x] 7.2 镜像同步失败任务不积压：`finishBatch` 删除本批次 FAILED/TIMEOUT 任务行（进行中仍实时可见），全失败批次连批次行一并删除——单测覆盖（含全失败批次零残留）；spec 同步至 registry-image-management delta（失败不积压 + 随实例删除）
