-- V2 权限控制与用户体系（任务 2.1）
-- 用户、角色、课题组、权限矩阵（角色×模块×操作）、审计日志（审计日志表已在 V1 创建）

-- 课题组
CREATE TABLE research_group (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    description  TEXT,
    mentor_id    BIGINT,                          -- 导师用户 ID
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_research_group_mentor ON research_group(mentor_id);
CREATE TRIGGER trg_research_group_updated_at BEFORE UPDATE ON research_group
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 用户（三角色：ADMIN / MENTOR / STUDENT）
CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    real_name       VARCHAR(100) NOT NULL,         -- 姓名
    role            VARCHAR(20) NOT NULL,           -- ADMIN / MENTOR / STUDENT
    student_id      VARCHAR(50),                    -- 工号/学号
    email           VARCHAR(100),
    phone           VARCHAR(30),
    group_id        BIGINT REFERENCES research_group(id), -- 学生所属课题组
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / DISABLED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_user_role ON app_user(role);
CREATE INDEX idx_app_user_group ON app_user(group_id);
CREATE TRIGGER trg_app_user_updated_at BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 课题组-学生关系（一个学生可在多个课题组，但通常一个；用关系表表达多对多）
-- 注：学生注册时 group_id 已指向主课题组，此表支持多组场景
CREATE TABLE group_member (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT NOT NULL REFERENCES research_group(id),
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(group_id, user_id)
);

-- 权限模块定义
CREATE TABLE permission_module (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE,       -- physical-instance / container / image / ...
    name        VARCHAR(100) NOT NULL,
    description TEXT
);

-- 权限矩阵（角色 × 模块 × 操作）
CREATE TABLE permission_matrix (
    id          BIGSERIAL PRIMARY KEY,
    role        VARCHAR(20) NOT NULL,               -- ADMIN / MENTOR / STUDENT
    module_id   BIGINT NOT NULL REFERENCES permission_module(id),
    can_view    BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit    BOOLEAN NOT NULL DEFAULT FALSE,
    can_delete  BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(role, module_id)
);

-- 初始化权限模块
INSERT INTO permission_module (code, name, description) VALUES
    ('physical-instance', '物理实例', '物理实例状态与管理'),
    ('container', '容器', '容器配置与生命周期'),
    ('image', '镜像', '镜像管理与公共镜像库'),
    ('storage-pool', '存储池', '存储池创建共享迁移'),
    ('ticket', '工单', '特需工单'),
    ('group', '课题组', '课题组信息与学生资源分配'),
    ('user', '用户管理', '用户与课题组管理'),
    ('permission', '权限矩阵', '权限矩阵配置'),
    ('announcement', '公告', '公告管理与查看'),
    ('monitoring', '监控', '实时监控与进程查看'),
    ('audit', '审计日志', '审计日志查询'),
    ('notification', '未读消息', '未读消息收件箱')
ON CONFLICT (code) DO NOTHING;

-- 初始化默认权限矩阵
-- 管理员：全部模块全部操作
INSERT INTO permission_matrix (role, module_id, can_view, can_edit, can_delete)
SELECT 'ADMIN', id, TRUE, TRUE, TRUE FROM permission_module
ON CONFLICT (role, module_id) DO NOTHING;

-- 学生：物理实例状态(查看)、容器(全部)、镜像(全部)、存储池(全部)、工单(全部)、课题组(查看)、公告(查看)、监控(查看)、未读消息(查看)
INSERT INTO permission_matrix (role, module_id, can_view, can_edit, can_delete)
SELECT 'STUDENT', pm.id,
    CASE WHEN pm.code IN ('physical-instance','container','image','storage-pool','ticket','group','announcement','monitoring','notification') THEN TRUE ELSE FALSE END,
    CASE WHEN pm.code IN ('container','image','storage-pool','ticket') THEN TRUE ELSE FALSE END,
    CASE WHEN pm.code IN ('container','image','storage-pool','ticket') THEN TRUE ELSE FALSE END
FROM permission_module pm
ON CONFLICT (role, module_id) DO NOTHING;

-- 导师：学生全部 + 课题组(编辑) + 监控(查看)
INSERT INTO permission_matrix (role, module_id, can_view, can_edit, can_delete)
SELECT 'MENTOR', pm.id,
    CASE WHEN pm.code IN ('physical-instance','container','image','storage-pool','ticket','group','announcement','monitoring','notification') THEN TRUE ELSE FALSE END,
    CASE WHEN pm.code IN ('container','image','storage-pool','ticket','group') THEN TRUE ELSE FALSE END,
    CASE WHEN pm.code IN ('container','image','storage-pool','ticket') THEN TRUE ELSE FALSE END
FROM permission_module pm
ON CONFLICT (role, module_id) DO NOTHING;

-- 初始管理员账号（密码: admin123 的 BCrypt 哈希，首次登录后应修改）
INSERT INTO app_user (username, password_hash, real_name, role, student_id, email)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '系统管理员', 'ADMIN', 'admin', 'admin@nexcompute.local')
ON CONFLICT (username) DO NOTHING;
