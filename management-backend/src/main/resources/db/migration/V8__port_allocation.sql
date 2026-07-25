-- V8 端口分配表（任务 10.2）
-- per-machine 分配表，管理端维护，防止单机端口冲突

CREATE TABLE port_allocation (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id),
    container_id    BIGINT REFERENCES container(id),
    container_port  INTEGER NOT NULL,              -- 容器内端口（如 22、8888）
    host_port       INTEGER NOT NULL,              -- 分配的宿主端口
    allocated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(instance_id, host_port)
);
CREATE INDEX idx_port_allocation_instance ON port_allocation(instance_id);
CREATE INDEX idx_port_allocation_container ON port_allocation(container_id);
