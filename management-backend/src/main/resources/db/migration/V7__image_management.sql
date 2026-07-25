-- V7 镜像管理（任务 9.1）
-- 镜像元数据（归属用户、名称、标签、大小、可见性、tar 文件路径）、公共镜像库

-- 镜像元数据
CREATE TABLE image_metadata (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,             -- 镜像名
    tag             VARCHAR(100) NOT NULL DEFAULT 'latest',
    owner_id        BIGINT,                             -- 归属用户（公共镜像为空）
    owner_name      VARCHAR(100),
    group_id        BIGINT,                             -- 归属用户课题组（可见性过滤用）
    size_bytes      BIGINT,                             -- tar 文件大小
    tar_path        VARCHAR(500),                       -- 管理端 tar 文件路径
    is_public       BOOLEAN NOT NULL DEFAULT FALSE,    -- 是否公共镜像
    source_container VARCHAR(100),                      -- 来源容器（commit 时）
    checksum        VARCHAR(64),                        -- tar SHA-256
    status          VARCHAR(20) NOT NULL DEFAULT 'READY', -- UPLOADING / READY / FAILED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_image_metadata_name_tag_owner ON image_metadata(name, tag, COALESCE(owner_id, -1));
CREATE INDEX idx_image_metadata_owner ON image_metadata(owner_id);
CREATE INDEX idx_image_metadata_group ON image_metadata(group_id);
CREATE INDEX idx_image_metadata_public ON image_metadata(is_public);

-- 镜像共享关系
CREATE TABLE image_share (
    id              BIGSERIAL PRIMARY KEY,
    image_id        BIGINT NOT NULL REFERENCES image_metadata(id),
    shared_to_user_id BIGINT NOT NULL REFERENCES app_user(id),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(image_id, shared_to_user_id)
);
CREATE INDEX idx_image_share_user ON image_share(shared_to_user_id);

-- 公共镜像同步状态（每台受控端对每个公共镜像的同步记录）
CREATE TABLE public_image_sync (
    id              BIGSERIAL PRIMARY KEY,
    image_id        BIGINT NOT NULL REFERENCES image_metadata(id),
    instance_id     BIGINT NOT NULL REFERENCES physical_instance(id),
    sync_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / SYNCED / FAILED
    synced_at       TIMESTAMPTZ,
    UNIQUE(image_id, instance_id)
);
