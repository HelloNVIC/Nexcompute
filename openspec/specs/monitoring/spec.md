# monitoring Specification

## Purpose
TBD - created by archiving change build-nexcompute-platform. Update Purpose after archive.
## Requirements
### Requirement: 实时状态采集与存储

管理端 SHALL 采集各受控物理机的实时状态（CPU 占用、GPU 占用、CPU 温度、GPU 温度、内存占用），保存至数据库（历史数据持久化）。采集粒度 SHALL 可配置，默认 5 秒。状态数据通过受控端心跳上报。

#### Scenario: 状态数据持久化
- **WHEN** 受控端上报心跳状态数据
- **THEN** 管理端将状态快照存入数据库
- **AND** 历史数据保留以支持趋势回溯

#### Scenario: 可配置采集粒度
- **WHEN** 管理员设置监控数据采集粒度（如 3 秒、5 秒、10 秒）
- **THEN** 系统按配置的粒度存储与展示状态数据
- **AND** 默认粒度为 5 秒

### Requirement: 历史数据保留策略

监控历史数据 SHALL 保留近 30 日。超过 30 日的历史数据 SHALL 自动清理。

#### Scenario: 自动清理过期数据
- **WHEN** 监控历史数据超过 30 日
- **THEN** 系统自动清理超期数据
- **AND** 清理可通过定时调度任务执行

### Requirement: 实时状态 SSE 推送

管理端 SHALL 通过 SSE（Server-Sent Events）向前端推送实时状态数据。前端通过 ApexCharts 展示实时变化曲线。

#### Scenario: 实时状态图表展示
- **WHEN** 用户在物理实例状态页面查看某在线实例
- **THEN** 前端通过 SSE 接收实时状态推送
- **AND** ApexCharts 展示该实例 CPU/GPU 占用与温度、内存占用的实时变化曲线

#### Scenario: 历史状态查看
- **WHEN** 用户查看某实例的历史状态
- **THEN** 系统从数据库读取近 30 日历史数据并展示该实例的状态趋势图

### Requirement: 进程列表展示

管理端 SHALL 能展示进程列表，进程查看范围按角色控制：管理员可查看 Windows 宿主机全量进程；学生仅可查看自己 Docker 容器内的进程；导师可查看自己课题组学生 Docker 容器内的进程。

#### Scenario: 管理员查看宿主机进程
- **WHEN** 管理员在物理实例状态中查看进程
- **THEN** 系统展示该实例 Windows 宿主机的全量进程列表（PID、名称、CPU/内存占用等）

#### Scenario: 学生查看自己容器进程
- **WHEN** 学生在物理实例状态中查看进程
- **THEN** 系统展示该学生自己 Docker 容器内的进程列表
- **AND** 不展示宿主机进程或其他用户容器进程

#### Scenario: 导师查看学生容器进程
- **WHEN** 导师在物理实例状态中查看进程
- **THEN** 系统展示该导师课题组学生 Docker 容器内的进程列表

### Requirement: 用户数与容器数统计展示

管理端 SHALL 在物理实例状态中展示每台机器的用户数与容器数，并以「另有 N 用户 M 容器」形式展示其他用户的聚合数量。

#### Scenario: 查看实例统计
- **WHEN** 用户查看物理实例列表
- **THEN** 每台实例展示当前用户数与容器数
- **AND** 展示「另有 N 用户 M 容器」的聚合统计


### Requirement: 结构化 GPU 指标持久化

管理端 SHALL 将心跳上报的结构化 GPU 指标（型号、总显存、已用显存、利用率、温度）持久化至监控历史数据，包括 `monitoring_history` 中的 GPU 显存字段（`gpu_memory_total`、`gpu_memory_used`）。历史趋势查询 SHALL 能展示 GPU 显存使用趋势。

#### Scenario: GPU 显存入库
- **WHEN** 受控端心跳携带结构化 GPU 指标（含总显存与已用显存）
- **THEN** 管理端将 GPU 总显存与已用显存写入 monitoring_history
- **AND** 历史 GPU 显存趋势可被查询与展示

#### Scenario: 无 GPU 指标时降级
- **WHEN** 心跳未携带结构化 GPU 显存（如受控端无 GPU 或采集失败）
- **THEN** 监控历史 GPU 显存字段留空，不影响其他指标持久化
