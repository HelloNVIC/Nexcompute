## ADDED Requirements

### Requirement: 结构化 GPU 指标持久化

管理端 SHALL 将心跳上报的结构化 GPU 指标（型号、总显存、已用显存、利用率、温度）持久化至监控历史数据，包括 `monitoring_history` 中的 GPU 显存字段（`gpu_memory_total`、`gpu_memory_used`）。历史趋势查询 SHALL 能展示 GPU 显存使用趋势。

#### Scenario: GPU 显存入库
- **WHEN** 受控端心跳携带结构化 GPU 指标（含总显存与已用显存）
- **THEN** 管理端将 GPU 总显存与已用显存写入 monitoring_history
- **AND** 历史 GPU 显存趋势可被查询与展示

#### Scenario: 无 GPU 指标时降级
- **WHEN** 心跳未携带结构化 GPU 显存（如受控端无 GPU 或采集失败）
- **THEN** 监控历史 GPU 显存字段留空，不影响其他指标持久化
