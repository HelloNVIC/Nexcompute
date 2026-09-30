-- V35 registry-image-distribution：镜像分发改为私有仓库
-- image_metadata 增量列（纯加列带默认值，旧代码可回滚兼容）：
-- 1) distribution：分发方式 TAR（存量 tar 镜像）/ REGISTRY（私有仓库镜像），默认 TAR。
-- 2) registry_valid：仓库有效性检查结论（null=未检查，TAR 镜像恒 null；true/false 为检查结论）。
-- 3) registry_checked_at：最近一次有效性检查时间。

ALTER TABLE image_metadata
    ADD COLUMN IF NOT EXISTS distribution VARCHAR(20) NOT NULL DEFAULT 'TAR';

ALTER TABLE image_metadata
    ADD COLUMN IF NOT EXISTS registry_valid BOOLEAN;

ALTER TABLE image_metadata
    ADD COLUMN IF NOT EXISTS registry_checked_at TIMESTAMPTZ;

COMMENT ON COLUMN image_metadata.distribution IS '镜像分发方式：TAR=管理端 tar 经 file-transfer+docker load 分发（存量）；REGISTRY=私有仓库 docker pull 分发';
COMMENT ON COLUMN image_metadata.registry_valid IS '仓库有效性（经 Registry v2 API 检查镜像是否存在）：null=未检查（TAR 镜像恒 null），true=有效，false=无效（未推送或已从仓库移除）';
COMMENT ON COLUMN image_metadata.registry_checked_at IS '最近一次仓库有效性检查时间';
