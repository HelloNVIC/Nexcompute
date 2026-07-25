-- V3 物理实例与连接凭证（任务 4.1）
-- 受控端↔管理端鉴权（凭证/证书），物理实例注册

-- 物理实例（受控端）
CREATE TABLE physical_instance (
    id              BIGSERIAL PRIMARY KEY,
    instance_number VARCHAR(50) NOT NULL UNIQUE,      -- 物理机编号（如 01）
    machine_name    VARCHAR(100),                      -- 机器名
    ip_address      VARCHAR(50),                       -- 物理机 IP（直连模式用）
    os_info         VARCHAR(200),                      -- 操作系统信息
    gpu_info        VARCHAR(500),                      -- GPU 信息
    connect_mode    VARCHAR(20) NOT NULL DEFAULT 'direct', -- direct / tunnel
    status          VARCHAR(20) NOT NULL DEFAULT 'OFFLINE', -- ONLINE / OFFLINE
    agent_version   VARCHAR(50),
    last_heartbeat  TIMESTAMPTZ,
    -- 最后心跳状态快照（JSON）
    last_status     JSONB,
    -- 本地管理员密码哈希（管理端设置，经 WS 下发受控端）
    local_admin_password_hash VARCHAR(255),
    storage_root    VARCHAR(500),                      -- 受控端存储池根目录
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_physical_instance_status ON physical_instance(status);
CREATE TRIGGER trg_physical_instance_updated_at BEFORE UPDATE ON physical_instance
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 连接凭证（受控端注册时分配的 token）
CREATE TABLE agent_credential (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT UNIQUE REFERENCES physical_instance(id),
    token           VARCHAR(128) NOT NULL UNIQUE,      -- 鉴权 token
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_agent_credential_token ON agent_credential(token);
