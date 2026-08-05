-- V31 容器内挂载点（storage-pool mount target）
-- 1) image_metadata 增 mount_point：镜像推荐的"存储池在容器内的挂载路径"，创建容器选该镜像时自动预填。
-- 2) container 增 mount_point：容器实际使用的容器内挂载点（用户可在创建表单中覆盖镜像默认值）。
--    受控端 docker run 时作为 bind mount 的 Target（此前硬编码 /workspace）。

ALTER TABLE image_metadata
    ADD COLUMN IF NOT EXISTS mount_point VARCHAR(500);

ALTER TABLE container
    ADD COLUMN IF NOT EXISTS mount_point VARCHAR(500);

COMMENT ON COLUMN image_metadata.mount_point IS '镜像推荐的容器内挂载点（存储池映射到容器内的路径，创建容器时自动预填，可覆盖）';
COMMENT ON COLUMN container.mount_point IS '存储池在容器内的挂载点（docker bind mount Target，空则回退 /workspace）';
