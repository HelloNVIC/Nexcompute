-- V12 修正初始管理员密码（admin123 的 BCrypt 哈希）
-- 原 V2 中的哈希为示例值，此处替换为正确的 admin123 哈希
UPDATE app_user
SET password_hash = '$2b$10$FHyls8K4bVIjr7DrBWmEweczGAn5wtzi94NNvrfyXPnTYBxVISH.K'
WHERE username = 'admin';
