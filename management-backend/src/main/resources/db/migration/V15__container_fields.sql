-- V15 容器表增加项目名与表单快照字段（任务 4）
ALTER TABLE container ADD COLUMN IF NOT EXISTS project_name VARCHAR(100);
ALTER TABLE container ADD COLUMN IF NOT EXISTS form_snapshot TEXT;

COMMENT ON COLUMN container.project_name IS '项目名（容器命名：学号-项目名-随机串）';
COMMENT ON COLUMN container.form_snapshot IS '创建时原始表单配置快照（JSON，供容器配置页回显）';
