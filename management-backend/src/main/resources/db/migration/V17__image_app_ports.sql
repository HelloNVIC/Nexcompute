-- V17 镜像应用端口（platform-improvements 任务 3.1）
-- image_metadata 增 app_ports（JSONB int[]）：上传 tar 时解析 ExposedPorts 并存储，
-- 供容器创建时自动预填"容器内端口"。

ALTER TABLE image_metadata
    ADD COLUMN app_ports JSONB;

COMMENT ON COLUMN image_metadata.app_ports IS '镜像应用端口列表（int[]，来自 tar ExposedPorts 解析）';
