-- V14 默认测试账号（任务：默认有管理员、测试导师、测试学生账号）
-- 密码均为 admin123（BCrypt 哈希）
-- 管理员 admin 已在 V2 创建，此处补充测试导师与测试学生

-- 测试导师课题组
INSERT INTO research_group (id, name, description, mentor_id)
VALUES (1, 'AI 算法课题组', '测试课题组-导师为 mentor', NULL)
ON CONFLICT (id) DO NOTHING;

-- 测试导师账号（密码 admin123）
INSERT INTO app_user (id, username, password_hash, real_name, role, student_id, email, group_id, status)
VALUES (2, 'mentor', '$2b$10$FHyls8K4bVIjr7DrBWmEweczGAn5wtzi94NNvrfyXPnTYBxVISH.K', '测试导师', 'MENTOR', 'T001', 'mentor@nexcompute.local', NULL, 'ACTIVE')
ON CONFLICT (username) DO NOTHING;

-- 关联导师到课题组
UPDATE research_group SET mentor_id = 2 WHERE id = 1;

-- 测试学生账号（密码 admin123，属于导师课题组）
INSERT INTO app_user (id, username, password_hash, real_name, role, student_id, email, group_id, status)
VALUES (3, 'student', '$2b$10$FHyls8K4bVIjr7DrBWmEweczGAn5wtzi94NNvrfyXPnTYBxVISH.K', '测试学生', 'STUDENT', 'S001', 'student@nexcompute.local', 1, 'ACTIVE')
ON CONFLICT (username) DO NOTHING;

-- 课题组-学生关系
INSERT INTO group_member (group_id, user_id)
VALUES (1, 3)
ON CONFLICT DO NOTHING;
