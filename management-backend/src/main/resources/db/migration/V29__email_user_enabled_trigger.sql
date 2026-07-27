-- V29 email-notification：补种"用户启用"触发键全局开关
-- V28 原仅种 7 触发键；启用账户亦发邮件（mandatory，用户不可关），补种第 8 键。
-- 注：isGlobalEnabled 对缺失键默认返回 true，此处种子仅为一致性与可持久化切换。

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('email.trigger.USER_ENABLED.enabled', 'true', '邮件触发开关：用户账户已启用（mandatory，用户不可关闭，仅全局开关生效）')
ON CONFLICT (config_key) DO NOTHING;
