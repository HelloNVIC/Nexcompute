-- V16 用户/课题组资源配额（platform-improvements 任务 5.1）
-- 每容器可配置上限（CPU 核数/内存 MB/GPU 显存 MB/SHM MB），可空=不限。
-- 有效配额 = 用户配额（若设）-> 课题组配额（若设）-> 目标物理机总容量（心跳上报）。

CREATE TABLE resource_quota (
    id                  BIGSERIAL PRIMARY KEY,
    scope               VARCHAR(10) NOT NULL,                 -- USER / GROUP
    owner_id            BIGINT NOT NULL,                       -- 用户 ID 或课题组 ID
    max_cpu_cores       REAL,                                  -- CPU 核数上限（可空=不限）
    max_memory_mb       BIGINT,                                -- 内存 MB 上限（可空=不限）
    max_gpu_memory_mb   BIGINT,                                -- GPU 显存 MB 上限（可空=不限）
    max_shm_mb          BIGINT,                                -- /dev/shm MB 上限（可空=不限）
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(scope, owner_id)
);
CREATE INDEX idx_resource_quota_owner ON resource_quota(scope, owner_id);

COMMENT ON TABLE resource_quota IS '用户/课题组资源配额（每容器上限，可空=不限）';
