-- V24 系统信息（platform-refinements #5）：维护人/维护电话/责任人/责任人电话，管理员可设置
CREATE TABLE system_info (
    id               SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    maintainer       VARCHAR(100),
    maintainer_phone VARCHAR(30),
    owner            VARCHAR(100),
    owner_phone      VARCHAR(30),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO system_info (id) VALUES (1) ON CONFLICT DO NOTHING;
COMMENT ON TABLE system_info IS '系统信息（单行，管理员设置，其他用户只读）';
