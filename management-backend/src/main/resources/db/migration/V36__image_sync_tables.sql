-- V36 image-bulk-sync：镜像"同步到所有机器"批次与任务表（agent-defaults 同 bundle）
-- 1) image_sync_batch：一次"同步到所有机器"操作对应一个批次（发起人 + 镜像引用快照 + 状态）。
--    每镜像同时仅一个 RUNNING 批次（部分唯一索引硬约束，服务层前置校验双保险）。
-- 2) image_sync_task：批次内每台物理实例一个任务。在线实例经 WS 下发 image.pull 并回传进度；
--    离线实例直接 OFFLINE_SKIPPED。终态：PULLED/ALREADY_EXISTS/FAILED/TIMEOUT/OFFLINE_SKIPPED。
-- SQL 全程不含 dollar-brace 占位符（含注释/字符串），避免 Flyway placeholder 未配置致启动失败。

-- ============ 同步批次 ============
CREATE TABLE image_sync_batch (
    id           BIGSERIAL PRIMARY KEY,
    image_id     BIGINT NOT NULL REFERENCES image_metadata(id),  -- 目标镜像
    image_ref    VARCHAR(500) NOT NULL,                          -- 拉取引用快照（仓库地址/镜像名:标签）
    initiated_by BIGINT NOT NULL REFERENCES app_user(id),        -- 发起管理员（app_user.id）
    status       VARCHAR(20) NOT NULL DEFAULT 'RUNNING',         -- RUNNING/DONE
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ                                     -- 全部任务终态后回填
);

-- 每镜像同时至多一个进行中批次（部分唯一索引）
CREATE UNIQUE INDEX uq_image_sync_batch_running ON image_sync_batch(image_id) WHERE status = 'RUNNING';

COMMENT ON TABLE image_sync_batch IS '镜像同步批次：一次"同步到所有机器"操作（发起人/镜像引用快照/状态）';
COMMENT ON COLUMN image_sync_batch.image_id IS '目标镜像（image_metadata.id）';
COMMENT ON COLUMN image_sync_batch.image_ref IS '拉取引用快照，如 10.13.66.25:5000/lab404-jupyter:0.1';
COMMENT ON COLUMN image_sync_batch.initiated_by IS '发起管理员（app_user.id）';
COMMENT ON COLUMN image_sync_batch.status IS '批次状态：RUNNING=进行中，DONE=全部任务终态';
COMMENT ON COLUMN image_sync_batch.finished_at IS '批次完成时间（全部任务终态后回填）';

-- ============ 同步任务（每实例一行） ============
CREATE TABLE image_sync_task (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        BIGINT NOT NULL REFERENCES image_sync_batch(id), -- 所属批次
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id), -- 目标物理实例
    instance_number VARCHAR(50) NOT NULL,                        -- 实例编号快照（展示用）
    command_id      VARCHAR(64),                                 -- WS 命令 ID（离线跳过任务为空）
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',      -- PENDING/PULLING/PULLED/ALREADY_EXISTS/FAILED/TIMEOUT/OFFLINE_SKIPPED
    percent         INT,                                         -- 拉取进度百分比（0-100）
    last_text       VARCHAR(500),                                -- 最近层状态文本（如 a1b2c3: Downloading 45%）
    error_message   VARCHAR(1000),                               -- 失败/超时原因
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ                                  -- 任务终态时间
);

CREATE INDEX ix_image_sync_task_batch ON image_sync_task(batch_id);

COMMENT ON TABLE image_sync_task IS '镜像同步任务：批次内每台物理实例一行（在线拉取/离线跳过）';
COMMENT ON COLUMN image_sync_task.batch_id IS '所属批次（image_sync_batch.id）';
COMMENT ON COLUMN image_sync_task.instance_id IS '目标物理实例（physical_instance.id）';
COMMENT ON COLUMN image_sync_task.instance_number IS '实例编号快照（展示用）';
COMMENT ON COLUMN image_sync_task.command_id IS 'WS 命令 ID（commandId，离线跳过任务为空）';
COMMENT ON COLUMN image_sync_task.status IS '任务状态：PENDING=待下发，PULLING=拉取中，PULLED=已拉取，ALREADY_EXISTS=本地已存在，FAILED=失败，TIMEOUT=超时，OFFLINE_SKIPPED=离线跳过';
COMMENT ON COLUMN image_sync_task.percent IS '拉取进度百分比（0-100）';
COMMENT ON COLUMN image_sync_task.last_text IS '最近层状态文本（受控端 image.pull 节流回传）';
COMMENT ON COLUMN image_sync_task.error_message IS '失败/超时原因';
COMMENT ON COLUMN image_sync_task.finished_at IS '任务终态时间';
