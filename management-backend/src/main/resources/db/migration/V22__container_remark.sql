-- V22 容器备注字段（platform-refinements #1）
ALTER TABLE container ADD COLUMN remark VARCHAR(500);
COMMENT ON COLUMN container.remark IS '容器备注（用户自定义）';
