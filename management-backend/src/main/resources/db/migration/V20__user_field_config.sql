-- V20 用户信息必填项配置（platform-refinements #5）
-- 管理员在"权限矩阵配置"页管理哪些用户字段为必填；username/role 始终必填，不在此配置。
CREATE TABLE user_field_config (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    real_name   BOOLEAN NOT NULL DEFAULT TRUE,
    student_id  BOOLEAN NOT NULL DEFAULT FALSE,
    email       BOOLEAN NOT NULL DEFAULT FALSE,
    phone       BOOLEAN NOT NULL DEFAULT FALSE,
    group_id    BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO user_field_config (id) VALUES (1) ON CONFLICT DO NOTHING;
COMMENT ON TABLE user_field_config IS '用户信息必填项配置（单行，管理员管理）';
