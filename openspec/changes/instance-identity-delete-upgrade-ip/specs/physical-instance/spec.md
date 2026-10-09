# Spec Delta

## MODIFIED Requirements

### Requirement: 物理实例注册与编号

受控端首次连接管理端时 SHALL 自动注册为物理实例并生成唯一物理机编号。物理实例身份 SHALL 由机器指纹决定（SMBIOS UUID 优先，其次 MachineGuid 机器码），物理机编号仅为管理员可修改的显示标签（MUST 全系统唯一），不再作为实例身份。同一指纹再次注册 SHALL 复用既有实例记录（含编号与凭证），并将 IP 地址、主机名、OS 信息、GPU 信息、受控端版本、存储池根目录等可变属性更新为最新上报值。指纹缺失时方创建新实例。编号用于存储池命名等场景。

#### Scenario: 自动注册生成编号
- **WHEN** 受控端首次连接管理端并通过鉴权（无指纹可匹配）
- **THEN** 管理端创建物理实例记录并自动生成唯一编号
- **AND** 编号回传给受控端持久化

#### Scenario: 指纹命中复用
- **WHEN** 已注册机器再次注册，SMBIOS UUID 或机器码与既有实例一致
- **THEN** 系统复用既有实例记录、编号与凭证
- **AND** 不产生重复物理实例

#### Scenario: 机器码单独命中复用
- **WHEN** SMBIOS UUID 缺失，但机器码（MachineGuid）与既有实例一致（MAC 是否匹配不影响判定）
- **THEN** 系统复用既有实例记录与凭证
- **AND** IP、主机名等可变属性更新为最新上报值

#### Scenario: IP 或主机名变化不新建
- **WHEN** 同一指纹的机器更换 IP 地址或主机名后心跳
- **THEN** 既有实例记录的对应字段更新
- **AND** 不创建新实例

#### Scenario: 管理员修改编号
- **WHEN** 管理员在物理实例管理中修改某实例编号
- **THEN** 系统校验新编号全系统唯一
- **AND** 校验通过后更新编号，关联的存储池命名同步更新

#### Scenario: 编号冲突拒绝
- **WHEN** 管理员试图将编号改为已存在的编号
- **THEN** 系统拒绝修改并提示编号冲突

### Requirement: 硬件指纹来源与稳定性

受控端硬件指纹 SHALL 由稳定硬件标识组合而成，用于物理实例注册去重。系统 SHALL 优先采用主板 BIOS 决定的 SMBIOS UUID 作为主指纹，MAC 地址与操作系统 MachineGuid 作为辅助回退指纹。SMBIOS UUID 采集 SHALL 经 PowerShell CIM（Get-CimInstance Win32_ComputerSystemProduct）优先获取、wmic 命令兜底——wmic 自 Windows 11 25H2 起被移除，仅靠 wmic 会导致主指纹在新型系统上静默缺失。机器码（MachineGuid）SHALL 在系统内唯一（部分唯一索引，排除空值），作为 SMBIOS 缺失时的身份判定依据。指纹缺失或为空时 SHALL 按回退链降级匹配，不阻断注册。

#### Scenario: 优先 SMBIOS UUID 作为主指纹
- **WHEN** 受控端采集硬件指纹
- **THEN** 受控端采集 SMBIOS UUID 作为主指纹
- **AND** 同时采集 MAC 与 MachineGuid 作为辅助指纹

#### Scenario: CIM 采集主指纹
- **WHEN** 受控端在 wmic 不可用的系统（如 Windows 11 25H2）上采集 SMBIOS UUID
- **THEN** 受控端经 PowerShell CIM 成功获取 SMBIOS UUID
- **AND** 主指纹不因 wmic 缺失而丢失

#### Scenario: 主指纹缺失回退
- **WHEN** SMBIOS UUID 为空或全 0（如部分虚拟机）
- **THEN** 注册去重回退至 MachineGuid（机器码）单独匹配
- **AND** MachineGuid 亦缺失时回退至 MAC 匹配
- **AND** 不阻断受控端注册

#### Scenario: 换网卡不重复注册
- **WHEN** 同一物理机更换网卡（MAC 变化）但主板与系统不变
- **THEN** 系统按 SMBIOS UUID 复用既有物理实例编号与凭证
- **AND** 不产生重复物理实例

## ADDED Requirements

### Requirement: 物理实例删除

管理员 SHALL 能删除物理实例。删除前系统 SHALL 校验该实例未被占用，以下任一命中 SHALL 拒绝删除并提示各类占用计数：实例在线（受控端 WebSocket 已连接）、已分配用户（machine_allocation）、存在容器、存在存储池、存在端口分配记录、存在存储池迁移记录。镜像同步任务为纯派生记录，不作为硬性拦截：确认强制删除（force）时 SHALL 跳过其检查并连同删除。删除 SHALL 级联清理该实例的受控端鉴权凭证、监控历史、公共镜像同步状态、升级任务记录与镜像同步任务。删除操作 SHALL 记入审计日志。

#### Scenario: 在线实例拒绝删除
- **WHEN** 管理员尝试删除在线实例（受控端 WebSocket 已连接）
- **THEN** 系统拒绝并提示实例在线、需先退出受控端

#### Scenario: 已占用实例拒绝删除
- **WHEN** 管理员尝试删除的实例存在用户分配、容器或存储池等占用
- **THEN** 系统拒绝并提示各类占用计数（如"已分配 2 个用户、3 个容器"）

#### Scenario: 镜像同步任务可强制删除
- **WHEN** 管理员删除的实例存在镜像同步任务记录且确认强制删除
- **THEN** 系统跳过同步任务检查，同步任务记录随实例一并删除
- **AND** 未确认强制时系统提示存在同步任务及数量

#### Scenario: 删除成功级联清理
- **WHEN** 管理员删除离线且无占用的实例
- **THEN** 系统删除实例记录及其凭证、监控历史、公共镜像同步状态、升级任务记录、镜像同步任务
- **AND** 操作记入审计日志

#### Scenario: 非管理员拒绝
- **WHEN** 非管理员尝试删除物理实例
- **THEN** 系统拒绝（接口返回无权限，前端不展示删除入口）
