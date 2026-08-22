-- V34 密码管理：忘记密码邮箱验证码重置（password-management-and-id-validation D2）
-- 1) password_reset_otp：忘记密码流程的验证码行。用户凭工号/学号请求发送验证码，
--    系统生成 6 位数字码并经 BCrypt 哈希存 code_hash（不存明文），异步发至账号绑定邮箱；
--    用户凭验证码 + 新密码重置时校验哈希匹配、未过期、未消费、未达尝试上限。
-- 2) 限频：send-code 按 username 查最近一行 created_at，距今 < send-cooldown-seconds（默认 60）则不建码不发邮件。
--    每码校验失败 attempt_count 递增，达 max-attempts（默认 5）后置 consumed_at 作废。
-- 3) 清理：PasswordResetScheduler 每 cleanup-interval-hours（默认 6）删 expires_at < now - retain-days（默认 7）的行。
-- SQL 全程不含 dollar-brace 占位符（含注释/字符串），避免 Flyway placeholder 未配置致启动失败。
-- BCrypt 哈希码与平台密码同算法同 bean（PasswordEncoder），防库泄露后码可被离线暴破的窗口最小化。

CREATE TABLE password_reset_otp (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL,                 -- 工号/学号（app_user.username 登录标识，注册时与 studentId 同值）
    code_hash       VARCHAR(100) NOT NULL,                 -- 6 位数字验证码的 BCrypt 哈希（不存明文）
    expires_at      TIMESTAMPTZ  NOT NULL,                  -- 过期时间（默认发送时刻 +10 分钟）
    consumed_at     TIMESTAMPTZ,                            -- 消费时间（空表示未消费；重置成功或达尝试上限时置位）
    attempt_count   INT          NOT NULL DEFAULT 0,        -- 校验失败次数（达上限后置 consumed_at 作废）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()     -- 创建时间（send-code 限频按 username + created_at 判定）
);

CREATE INDEX ix_pwd_reset_otp_username_created ON password_reset_otp(username, created_at);
CREATE INDEX ix_pwd_reset_otp_expires ON password_reset_otp(expires_at);

COMMENT ON TABLE password_reset_otp IS '忘记密码验证码表：凭工号/学号发送 6 位数字验证码（BCrypt 哈希存）至绑定邮箱，校验通过后重置密码；按用户名限频、每码 5 次校验上限、过期/已消费由调度清理';
COMMENT ON COLUMN password_reset_otp.username IS '工号/学号（app_user.username 登录标识，注册时与 studentId 同值）';
COMMENT ON COLUMN password_reset_otp.code_hash IS '6 位数字验证码的 BCrypt 哈希（不存明文，与平台密码同算法同 bean）';
COMMENT ON COLUMN password_reset_otp.expires_at IS '过期时间（默认发送时刻 +10 分钟，过期后重置被拒）';
COMMENT ON COLUMN password_reset_otp.consumed_at IS '消费时间（空=未消费；重置成功或校验失败达上限后置位作废）';
COMMENT ON COLUMN password_reset_otp.attempt_count IS '校验失败次数（每次验证码错误 +1，达上限 5 后置 consumed_at 作废）';
COMMENT ON COLUMN password_reset_otp.created_at IS '创建时间（send-code 按 username + created_at 判定 60 秒限频窗口）';
