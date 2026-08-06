-- V32 NAS 分配模块：邀请门控的 TrueNAS 用户注册 + 管理员审批开通全流程
-- 1) nas_invitation：管理员创建的邀请令牌（标签/名额/有效期/撤销），凭令牌可注册 TrueNAS 用户。
--    名额消耗由应用层经单条条件 UPDATE 原子完成（used_count < max_uses AND revoked_at IS NULL AND expires_at > now），
--    并发不超发；过期/撤销/名额耗尽各自精确报错。
-- 2) nas_registration：公开提交的注册申请行。提交时 AES-GCM 加密密码暂存（password_enc），
--    不调 TrueNAS；管理员批准时解密 -> TrueNAS 建用户 -> 回填 id/uid -> 擦除密码 -> APPROVED。
--    5 态机：PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND。密码在 APPROVED/REJECTED/pending 过期后擦除，FAILED 保留可重试。
-- SQL 全程不含 dollar-brace 占位符（含注释/字符串），避免 Flyway placeholder 未配置致启动失败。

-- ============ 邀请令牌 ============
CREATE TABLE nas_invitation (
    id           BIGSERIAL PRIMARY KEY,
    token        VARCHAR(64) NOT NULL UNIQUE,                -- 邀请令牌（SecureRandom 生成，不可猜）
    label        VARCHAR(128) NOT NULL,                      -- 标签（便于管理员识别）
    max_uses      INT NOT NULL DEFAULT 1,                     -- 最大使用次数
    used_count    INT NOT NULL DEFAULT 0,                     -- 已使用次数
    expires_at   TIMESTAMPTZ NOT NULL,                       -- 过期时间
    created_by   BIGINT NOT NULL REFERENCES app_user(id),    -- 创建者（管理员 app_user.id）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),         -- 创建时间
    revoked_at   TIMESTAMPTZ                                 -- 撤销时间（空表示有效）
);

CREATE INDEX ix_nas_invitation_token ON nas_invitation(token);

COMMENT ON TABLE nas_invitation IS 'NAS 邀请令牌表：管理员创建的 TrueNAS 用户注册邀请，含标签/名额/有效期/撤销，凭令牌门控公开注册';
COMMENT ON COLUMN nas_invitation.token IS '邀请令牌（SecureRandom 生成，不可猜测）';
COMMENT ON COLUMN nas_invitation.label IS '标签（便于管理员识别邀请用途）';
COMMENT ON COLUMN nas_invitation.max_uses IS '最大使用次数（名额上限）';
COMMENT ON COLUMN nas_invitation.used_count IS '已使用次数（原子条件 UPDATE 递增，并发不超发）';
COMMENT ON COLUMN nas_invitation.expires_at IS '过期时间';
COMMENT ON COLUMN nas_invitation.created_by IS '创建者（管理员 app_user.id）';
COMMENT ON COLUMN nas_invitation.created_at IS '创建时间';
COMMENT ON COLUMN nas_invitation.revoked_at IS '撤销时间（空表示有效，撤销后兑换被拒）';

-- ============ 注册申请 ============
CREATE TABLE nas_registration (
    id              BIGSERIAL PRIMARY KEY,
    invitation_id   BIGINT NOT NULL REFERENCES nas_invitation(id), -- 所用邀请
    username        VARCHAR(64) NOT NULL,                    -- TrueNAS 用户名（POSIX 字符集）
    full_name       VARCHAR(255) NOT NULL,                   -- 姓名（传给 TrueNAS full_name）
    email           VARCHAR(255) NOT NULL,                   -- 邮箱
    phone           VARCHAR(64) NOT NULL,                    -- 手机号
    password_enc    TEXT,                                    -- AES-GCM 加密密码密文（含 nonce，开通/拒绝/过期后擦除）
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND
    truenas_user_id INT,                                     -- TrueNAS 用户 ID（开通后回填）
    truenas_uid      INT,                                     -- TrueNAS UID
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),      -- 提交时间
    reviewed_by     BIGINT REFERENCES app_user(id),          -- 审核人（管理员 app_user.id）
    reviewed_at     TIMESTAMPTZ,                             -- 审核时间
    reject_reason   VARCHAR(255),                            -- 拒绝原因
    provision_error TEXT                                     -- 开通失败错误信息
);

CREATE INDEX ix_nas_reg_invitation_id ON nas_registration(invitation_id);
CREATE INDEX ix_nas_reg_username ON nas_registration(username);
CREATE INDEX ix_nas_reg_email ON nas_registration(email);
CREATE INDEX ix_nas_reg_status ON nas_registration(status);
CREATE INDEX ix_nas_reg_status_submitted ON nas_registration(status, submitted_at);

COMMENT ON TABLE nas_registration IS 'NAS 注册申请表：凭邀请令牌公开提交，管理员审批后经 TrueNAS REST 开通，5 态机 PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND';
COMMENT ON COLUMN nas_registration.invitation_id IS '所用邀请（nas_invitation.id）';
COMMENT ON COLUMN nas_registration.username IS 'TrueNAS 用户名（POSIX 字符集，PENDING/APPROVED/FAILED 占用，REJECTED 释放）';
COMMENT ON COLUMN nas_registration.full_name IS '姓名（传给 TrueNAS full_name）';
COMMENT ON COLUMN nas_registration.email IS '邮箱';
COMMENT ON COLUMN nas_registration.phone IS '手机号';
COMMENT ON COLUMN nas_registration.password_enc IS 'AES-GCM 加密密码密文（base64-urlsafe(nonce12 + ct + tag)，开通/拒绝/pending 过期后擦除）';
COMMENT ON COLUMN nas_registration.status IS '状态：PENDING 待审批 / APPROVED 已开通 / REJECTED 已拒绝 / FAILED 开通失败可重试 / NOT_FOUND TrueNAS 用户已删';
COMMENT ON COLUMN nas_registration.truenas_user_id IS 'TrueNAS 用户 ID（开通后回填）';
COMMENT ON COLUMN nas_registration.truenas_uid IS 'TrueNAS UID（开通后回填）';
COMMENT ON COLUMN nas_registration.submitted_at IS '提交时间（pending 过期扫描据此判定）';
COMMENT ON COLUMN nas_registration.reviewed_by IS '审核人（管理员 app_user.id）';
COMMENT ON COLUMN nas_registration.reviewed_at IS '审核时间';
COMMENT ON COLUMN nas_registration.reject_reason IS '拒绝原因';
COMMENT ON COLUMN nas_registration.provision_error IS '开通失败错误信息（FAILED/NOT_FOUND 重试时记录）';
