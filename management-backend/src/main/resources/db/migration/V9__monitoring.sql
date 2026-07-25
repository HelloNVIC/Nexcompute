-- V9 监控历史数据（任务 11.1）
-- 心跳快照入库，历史趋势

CREATE TABLE monitoring_history (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id),
    instance_number VARCHAR(50) NOT NULL,
    cpu_usage       REAL,
    cpu_temp        REAL,
    gpu_usage       REAL,
    gpu_temp        REAL,
    memory_usage    REAL,
    memory_total    BIGINT,
    memory_used     BIGINT,
    gpu_memory_total BIGINT,
    gpu_memory_used  BIGINT,
    status_snapshot JSONB,                           -- 完整状态快照
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_monitoring_history_instance_time ON monitoring_history(instance_id, recorded_at DESC);
CREATE INDEX idx_monitoring_history_recorded_at ON monitoring_history(recorded_at);
