-- V4 课题组与注册链接（任务 3.1）
-- research_group、group_member 已在 V2 创建

-- 注册链接（UUID、次数、过期时间、状态）
CREATE TABLE registration_link (
    id               BIGSERIAL PRIMARY KEY,
    token            VARCHAR(64) NOT NULL UNIQUE,       -- UUID
    group_id         BIGINT NOT NULL REFERENCES research_group(id),
    creator_id       BIGINT NOT NULL REFERENCES app_user(id), -- 导师
    remaining_count  INTEGER NOT NULL,                  -- 剩余可用次数
    expire_at        TIMESTAMPTZ NOT NULL,               -- 过期时间
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / REVOKED / EXHAUSTED / EXPIRED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_registration_link_creator ON registration_link(creator_id);
CREATE INDEX idx_registration_link_group ON registration_link(group_id);

-- 机器分配关系（导师将物理实例分配给学生）
CREATE TABLE machine_allocation (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id),
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    group_id        BIGINT REFERENCES research_group(id),
    allocated_by    BIGINT NOT NULL REFERENCES app_user(id), -- 分配者（导师）
    allocated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(instance_id, user_id)
);
CREATE INDEX idx_machine_allocation_user ON machine_allocation(user_id);
CREATE INDEX idx_machine_allocation_instance ON machine_allocation(instance_id);
