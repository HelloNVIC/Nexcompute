# physical-instance Specification

## Purpose
TBD - created by archiving change build-nexcompute-platform. Update Purpose after archive.
## Requirements
### Requirement: 物理实例注册与编号

受控端首次连接管理端时 SHALL 自动注册为物理实例并生成唯一物理机编号。管理员可修改编号，但编号 MUST 在全系统内唯一。编号用于存储池命名等场景。

#### Scenario: 自动注册生成编号
- **WHEN** 受控端首次连接管理端并通过鉴权
- **THEN** 管理端创建物理实例记录并自动生成唯一编号
- **AND** 编号回传给受控端持久化

#### Scenario: 管理员修改编号
- **WHEN** 管理员在物理实例管理中修改某实例编号
- **THEN** 系统校验新编号全系统唯一
- **AND** 校验通过后更新编号，关联的存储池命名同步更新

#### Scenario: 编号冲突拒绝
- **WHEN** 管理员试图将编号改为已存在的编号
- **THEN** 系统拒绝修改并提示编号冲突

### Requirement: 物理实例状态监控

管理端 SHALL 能查看每个受控物理机的 CPU 占用、GPU 占用、CPU 温度、GPU 温度、内存占用、进程列表、各类配置信息。状态数据通过心跳上报。

#### Scenario: 查看物理实例状态
- **WHEN** 用户在物理实例状态页面查看某在线实例
- **THEN** 系统展示该实例最新的 CPU/GPU 占用与温度、内存占用、进程列表及配置信息

#### Scenario: 离线实例状态
- **WHEN** 用户查看离线实例
- **THEN** 系统展示最后一次心跳的状态快照并标记离线

### Requirement: 物理实例远程控制

管理端 SHALL 能对受控物理机执行重启、息屏操作。重启与息屏命令通过 WebSocket 命令通道下发。

#### Scenario: 远程重启
- **WHEN** 管理员触发某物理实例重启
- **THEN** 管理端通过 WebSocket 下发重启命令
- **AND** 受控端执行系统重启
- **AND** 操作记入审计日志

#### Scenario: 远程息屏
- **WHEN** 管理员触发某物理实例息屏
- **THEN** 管理端下发息屏命令，受控端执行息屏
- **AND** 操作记入审计日志

### Requirement: PowerShell 远程执行（管理员限定）

仅管理员 SHALL 能在物理实例管理中对受控物理机执行 PowerShell 命令。命令执行 MUST 记入审计日志（操作人、时间、目标实例、命令内容、执行结果）。

#### Scenario: 管理员执行 PowerShell
- **WHEN** 管理员在物理实例管理中对某实例输入并执行 PowerShell 命令
- **THEN** 管理端通过 WebSocket 下发命令，受控端执行并回传输出
- **AND** 管理端将命令内容与输出记入审计日志
- **AND** 管理员看到实时输出

#### Scenario: 非管理员拒绝执行
- **WHEN** 导师或学生尝试执行 PowerShell 命令
- **THEN** 系统拒绝并提示无权限

### Requirement: 用户数与容器数统计

管理端 SHALL 在物理实例状态中展示每台机器上的用户数与容器数。学生可查看其有权限访问的实例的统计。

#### Scenario: 查看实例用户数与容器数
- **WHEN** 用户查看某物理实例状态
- **THEN** 系统展示该实例当前的用户数与容器数

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

