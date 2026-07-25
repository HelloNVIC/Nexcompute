-- V18 platform-refinements 镜像元数据扩展、机器分配单容器内存上限、全局受控端管理密码
-- 1.1 image_metadata 增列：usage_instructions / note / project / source_worker_id / visibility
-- 1.2 machine_allocation 增列 per_container_memory_mb（导师为学生在某实例上设的单容器内存上限）
-- 1.3 新增全局 local_admin_password 单行配置表（明文存储）
-- 1.4 既有 local_admin_password_hash（按实例哈希）数据清空，改由全局明文密码统一管理

-- 1.1 image_metadata 扩展列
ALTER TABLE image_metadata
    ADD COLUMN usage_instructions TEXT,
    ADD COLUMN note VARCHAR(500),
    ADD COLUMN project VARCHAR(200),
    ADD COLUMN source_worker_id VARCHAR(100),
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';

COMMENT ON COLUMN image_metadata.usage_instructions IS '使用说明（用户上传/提交时填写）';
COMMENT ON COLUMN image_metadata.note IS '备注（commit 镜像时的备注）';
COMMENT ON COLUMN image_metadata.project IS '所属项目（commit 镜像时的项目名）';
COMMENT ON COLUMN image_metadata.source_worker_id IS '来源工号（commit 镜像时提交者的工号）';
COMMENT ON COLUMN image_metadata.visibility IS '可见性：PRIVATE（仅本人+管理员）/ SHARED_TO_ALL（全用户可见）/ SHARED（已显式共享）';

-- 既有公共镜像（is_public=true）置为 SHARED_TO_ALL 语义不变（仍走公共镜像库同步）
UPDATE image_metadata SET visibility = 'SHARED_TO_ALL' WHERE is_public = TRUE;

CREATE INDEX idx_image_metadata_visibility ON image_metadata(visibility);
CREATE INDEX idx_image_metadata_source_worker ON image_metadata(source_worker_id);

-- 1.2 machine_allocation 增 per_container_memory_mb（可空=不限）
ALTER TABLE machine_allocation
    ADD COLUMN per_container_memory_mb INTEGER;

COMMENT ON COLUMN machine_allocation.per_container_memory_mb IS '该学生在该实例上单容器内存上限（MB，可空=不限）';

-- 1.3 全局受控端管理密码（单行配置，明文存储）
CREATE TABLE local_admin_password (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    password    VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE local_admin_password IS '全局受控端管理密码（所有受控端共用，明文存储，用户明确要求明文）';

-- 1.4 既有按实例 local_admin_password_hash 清空（哈希语义已废弃，改由全局明文密码统一）
UPDATE physical_instance SET local_admin_password_hash = NULL;
