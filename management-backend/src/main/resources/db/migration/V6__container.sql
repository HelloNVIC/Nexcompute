-- V6 容器（任务 10.1，提前创建因存储池共享撤销检查需要）
-- 容器：归属用户、物理实例、镜像、存储池、资源限制、端口映射、SSH 密码、状态

CREATE TABLE container (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL UNIQUE,      -- Docker 容器名
    owner_id        BIGINT NOT NULL REFERENCES app_user(id),
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id),
    instance_number VARCHAR(50) NOT NULL,
    image_ref       VARCHAR(200) NOT NULL,             -- 镜像引用
    image_id        BIGINT,                             -- 关联镜像元数据（可空，公共镜像无）
    storage_pool_id BIGINT REFERENCES storage_pool(id),-- 挂载的存储池
    cpu_limit       REAL,                               -- CPU 硬限制（核数）
    memory_limit    BIGINT,                             -- 内存硬限制（字节）
    gpu_memory_limit INTEGER,                           -- GPU 显存软限制（MB）
    shm_size        BIGINT,                             -- /dev/shm 大小（字节）
    port_mappings   JSONB,                              -- 端口映射 [{containerPort, hostPort}]
    ssh_password    VARCHAR(100),                       -- 容器级 SSH 密码（加密存储）
    docker_id       VARCHAR(100),                       -- Docker 容器 ID
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED', -- CREATED / RUNNING / STOPPED / EXITED / REMOVED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_container_owner ON container(owner_id);
CREATE INDEX idx_container_instance ON container(instance_id);
CREATE INDEX idx_container_pool ON container(storage_pool_id);
CREATE INDEX idx_container_status ON container(status);
CREATE TRIGGER trg_container_updated_at BEFORE UPDATE ON container
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
