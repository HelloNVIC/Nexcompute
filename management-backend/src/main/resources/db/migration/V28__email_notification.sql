-- V28 email-notification：邮件提醒通道（7 触发键 + SMTP/品牌/Logo 配置 + 用户偏好 + 发送日志）
-- 1.1 user_email_pref：用户邮件偏好（仅记主动关闭项；mandatory 触发键不入表）
-- 1.2 email_log：每次邮件发送结果追踪
-- 1.3 system_config 种子 7 触发键全局开关（默认全开）
-- 1.4 system_config 种子 6 SMTP 键默认值（来自 application.yml nexcompute.email.* 默认）
-- 1.5 system_config 种子 品牌名/落款/Logo 文件名（默认兜底）

-- 1.1 用户邮件偏好表
CREATE TABLE IF NOT EXISTS user_email_pref (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    trigger_key  VARCHAR(50) NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_email_pref_user_trigger UNIQUE (user_id, trigger_key)
);
CREATE INDEX IF NOT EXISTS idx_user_email_pref_user_id ON user_email_pref(user_id);
COMMENT ON TABLE  user_email_pref IS '用户邮件偏好（仅记主动关闭项；注册/禁用 mandatory 不入表，查询时强制 true）';
COMMENT ON COLUMN user_email_pref.trigger_key IS 'EmailTrigger 枚举名（USER_REGISTERED 等不在此表）';
COMMENT ON COLUMN user_email_pref.enabled     IS 'true=发送，false=用户已关闭该类邮件';

-- 1.2 邮件发送日志表
CREATE TABLE IF NOT EXISTS email_log (
    id                 BIGSERIAL PRIMARY KEY,
    trigger_key        VARCHAR(50),
    recipient_user_id  BIGINT,
    recipient_email    VARCHAR(100),
    subject            VARCHAR(200),
    status             VARCHAR(20) NOT NULL,
    error              TEXT,
    sent_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_email_log_recipient_user_id ON email_log(recipient_user_id);
CREATE INDEX IF NOT EXISTS idx_email_log_trigger_key      ON email_log(trigger_key);
CREATE INDEX IF NOT EXISTS idx_email_log_status           ON email_log(status);
CREATE INDEX IF NOT EXISTS idx_email_log_sent_at          ON email_log(sent_at);
COMMENT ON TABLE  email_log IS '邮件发送日志（异步发送结果追踪，status=SUCCESS/FAILED）';
COMMENT ON COLUMN email_log.trigger_key       IS 'EmailTrigger 枚举名（测试发送为 null）';
COMMENT ON COLUMN email_log.status            IS 'SUCCESS / FAILED';
COMMENT ON COLUMN email_log.recipient_user_id IS '收件人用户 ID（测试发送可为 null）';

-- 1.3 7 触发键全局开关（默认全开；管理员可在系统信息页逐项关闭）
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('email.trigger.USER_REGISTERED.enabled',           'true', '邮件触发开关：用户注册（mandatory，用户不可关闭，仅全局开关生效）'),
    ('email.trigger.USER_DISABLED.enabled',             'true', '邮件触发开关：用户账户被禁用（mandatory，用户不可关闭，仅全局开关生效）'),
    ('email.trigger.INSTANCE_ALLOCATED.enabled',         'true', '邮件触发开关：用户被分配实例'),
    ('email.trigger.INSTANCE_DEALLOCATED.enabled',       'true', '邮件触发开关：用户实例分配被撤销'),
    ('email.trigger.STORAGE_POOL_MIGRATED.enabled',      'true', '邮件触发开关：用户存储池迁移'),
    ('email.trigger.IMAGE_PERMISSION_CHANGED.enabled',   'true', '邮件触发开关：用户镜像权限变化'),
    ('email.trigger.CONTAINER_PERMISSION_CHANGED.enabled','true', '邮件触发开关：用户容器权限变化')
ON CONFLICT (config_key) DO NOTHING;

-- 1.4 6 SMTP 键默认值（来自 application.yml nexcompute.email.* 默认；PASSWD 默认空，管理员经系统信息页填入）
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('email.smtp.from',     'cufel@cufe.edu.cn',  'SMTP 发件人地址'),
    ('email.smtp.host',     'smtp.exmail.qq.com', 'SMTP 服务器地址'),
    ('email.smtp.port',     '465',                'SMTP 端口'),
    ('email.smtp.protocol', 'smtps',              'SMTP 协议（smtps/smtp）'),
    ('email.smtp.user',     'cufel@cufe.edu.cn',  'SMTP 认证用户名'),
    ('email.smtp.passwd',   '',                   'SMTP 认证密码（明文存储，GET 接口脱敏，仅 PUT 接受明文）')
ON CONFLICT (config_key) DO NOTHING;

-- 1.5 品牌名 / 落款 / Logo 文件名（默认兜底：品牌名"合算 Nexcompute"，Logo 用 classpath 默认 PNG）
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('email.brand.name',          '合算 Nexcompute', '邮件品牌名（用于品牌标题/落款/主题）'),
    ('email.signature',           '- Nexcompute 管理平台
（此邮件由系统自动发送，请勿直接回复）', '邮件落款（多行文本）'),
    ('email.brand.logo_filename', '',                 'Logo 文件名（空=用 classpath 默认 PNG；非空用 storage 根目录下 email/ 子目录的已上传文件）')
ON CONFLICT (config_key) DO NOTHING;
