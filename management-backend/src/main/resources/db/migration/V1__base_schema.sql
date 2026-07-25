-- V1 基础设置：启用 UUID 扩展（注册链接使用 UUID）
-- 各业务表在后续迁移中按模块创建（V2: 用户/权限/审计，V3: 课题组/注册链接，...）

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 审计日志表（任务 2.1 建表的一部分，提前到 V1 因其为基础设施）
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    operator_id     BIGINT,
    operator_name   VARCHAR(100),
    operator_role   VARCHAR(20),
    action          VARCHAR(100) NOT NULL,      -- 操作类型
    target_type     VARCHAR(50),                -- 操作目标类型
    target_id       VARCHAR(100),               -- 操作目标 ID
    content         TEXT,                       -- 操作内容（JSON）
    result          VARCHAR(20) NOT NULL,       -- SUCCESS / FAILURE
    error_message   TEXT,
    ip_address      VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_operator ON audit_log(operator_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log(action);

-- 通用 updated_at 自动更新函数
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
