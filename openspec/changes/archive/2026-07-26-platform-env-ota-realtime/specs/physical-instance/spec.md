## ADDED Requirements

### Requirement: 物理实例受控端版本展示

管理端 SHALL 在物理实例列表中展示每台物理实例当前上报的受控端版本号。版本号来源于受控端心跳上报。

#### Scenario: 列表展示版本
- **WHEN** 用户查看物理实例列表
- **THEN** 每台物理实例展示其受控端版本号
- **AND** 离线实例展示最后一次心跳上报的版本号

### Requirement: 硬件指纹来源与稳定性

受控端硬件指纹 SHALL 由稳定硬件标识组合而成，用于物理实例注册去重。系统 SHALL 优先采用主板 BIOS 决定的 SMBIOS UUID 作为主指纹，MAC 地址与操作系统 MachineGuid 作为辅助回退指纹。指纹缺失或为空时 SHALL 按回退链降级匹配，不阻断注册。

#### Scenario: 优先 SMBIOS UUID 作为主指纹
- **WHEN** 受控端采集硬件指纹
- **THEN** 受控端采集 SMBIOS UUID 作为主指纹
- **AND** 同时采集 MAC 与 MachineGuid 作为辅助指纹

#### Scenario: 主指纹缺失回退
- **WHEN** SMBIOS UUID 为空或全 0（如部分虚拟机）
- **THEN** 注册去重回退至 MachineGuid 匹配
- **AND** MachineGuid 亦缺失时回退至 MAC 匹配
- **AND** 不阻断受控端注册

#### Scenario: 换网卡不重复注册
- **WHEN** 同一物理机更换网卡（MAC 变化）但主板与系统不变
- **THEN** 系统按 SMBIOS UUID 复用既有物理实例编号与凭证
- **AND** 不产生重复物理实例
