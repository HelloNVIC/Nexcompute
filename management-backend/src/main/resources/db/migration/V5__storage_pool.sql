-- V5 存储池（任务 8.1）
-- 存储池：归属用户、物理实例、项目名、路径、共享关系

CREATE TABLE storage_pool (
    id              BIGSERIAL PRIMARY KEY,
    pool_name       VARCHAR(200) NOT NULL,             -- 完整命名：物理机编号-工号/学号-项目名
    project_name    VARCHAR(100) NOT NULL,             -- 用户自定义项目名
    owner_id        BIGINT NOT NULL REFERENCES app_user(id),
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id),
    instance_number VARCHAR(50) NOT NULL,              -- 冗余编号便于命名
    user_student_id VARCHAR(50) NOT NULL,              -- 冗余工号/学号便于命名
    pool_path       VARCHAR(500),                      -- 受控端上的实际路径
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / MIGRATING / MIGRATED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(instance_id, owner_id, project_name)
);
CREATE INDEX idx_storage_pool_owner ON storage_pool(owner_id);
CREATE INDEX idx_storage_pool_instance ON storage_pool(instance_id);
CREATE TRIGGER trg_storage_pool_updated_at BEFORE UPDATE ON storage_pool
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 存储池共享关系
CREATE TABLE storage_pool_share (
    id              BIGSERIAL PRIMARY KEY,
    pool_id         BIGINT NOT NULL REFERENCES storage_pool(id),
    shared_to_user_id BIGINT NOT NULL REFERENCES app_user(id),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(pool_id, shared_to_user_id)
);
CREATE INDEX idx_storage_pool_share_pool ON storage_pool_share(pool_id);
CREATE INDEX idx_storage_pool_share_user ON storage_pool_share(shared_to_user_id);

-- 存储池迁移记录
CREATE TABLE storage_pool_migration (
    id              BIGSERIAL PRIMARY KEY,
    pool_id         BIGINT NOT NULL REFERENCES storage_pool(id),
    source_instance_id   BIGINT NOT NULL REFERENCES physical_instance(id),
    target_instance_id   BIGINT NOT NULL REFERENCES physical_instance(id),
    transfer_id     VARCHAR(64) NOT NULL,              -- 关联文件传输 ID
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / TRANSFERRING / COMPLETED / FAILED / CONFIRMED
    initiated_by    BIGINT NOT NULL REFERENCES app_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
