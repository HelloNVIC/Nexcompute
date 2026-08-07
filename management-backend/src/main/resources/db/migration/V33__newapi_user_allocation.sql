-- V33 NewAPI 用户分配模块：邀请门控的 NewAPI 用户注册 + 管理员审批开通全流程
-- 1) newapi_invitation：管理员创建的邀请令牌（标签/名额/有效期/撤销），凭令牌可注册 NewAPI 用户。
--    名额消耗由应用层经单条条件 UPDATE 原子完成（used_count < max_uses AND revoked_at IS NULL AND expires_at > now），
--    并发不超发；过期/撤销/名额耗尽各自精确报错。
-- 2) newapi_registration：公开提交的注册申请行。提交时 AES-GCM 加密密码暂存（password_enc），
--    不调 NewAPI；管理员批准时解密 -> NewAPI 建用户 -> search 回查拿 id 回填 -> 擦除密码 -> APPROVED。
--    5 态机：PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND。密码在 APPROVED/REJECTED/pending 过期后擦除，FAILED 保留可重试。
-- SQL 全程不含 dollar-brace 占位符（含注释/字符串），避免 Flyway placeholder 未配置致启动失败。
-- AES 密钥复用既有 PASSWORD_ENC_KEY（与 nas 共用，密文格式 base64-urlsafe(nonce12+ct+tag) 一致）。

-- ============ 邀请令牌 ============
CREATE TABLE newapi_invitation (
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

CREATE INDEX ix_newapi_invitation_token ON newapi_invitation(token);

COMMENT ON TABLE newapi_invitation IS 'NewAPI 邀请令牌表：管理员创建的 NewAPI 用户注册邀请，含标签/名额/有效期/撤销，凭令牌门控公开注册';
COMMENT ON COLUMN newapi_invitation.token IS '邀请令牌（SecureRandom 生成，不可猜测）';
COMMENT ON COLUMN newapi_invitation.label IS '标签（便于管理员识别邀请用途）';
COMMENT ON COLUMN newapi_invitation.max_uses IS '最大使用次数（名额上限）';
COMMENT ON COLUMN newapi_invitation.used_count IS '已使用次数（原子条件 UPDATE 递增，并发不超发）';
COMMENT ON COLUMN newapi_invitation.expires_at IS '过期时间';
COMMENT ON COLUMN newapi_invitation.created_by IS '创建者（管理员 app_user.id）';
COMMENT ON COLUMN newapi_invitation.created_at IS '创建时间';
COMMENT ON COLUMN newapi_invitation.revoked_at IS '撤销时间（空表示有效，撤销后兑换被拒）';

-- ============ 注册申请 ============
CREATE TABLE newapi_registration (
    id              BIGSERIAL PRIMARY KEY,
    invitation_id   BIGINT NOT NULL REFERENCES newapi_invitation(id), -- 所用邀请
    username        VARCHAR(64) NOT NULL,                    -- NewAPI 用户名
    display_name    VARCHAR(255) NOT NULL,                   -- 显示名（传给 NewAPI display_name）
    email           VARCHAR(255) NOT NULL,                   -- 邮箱
    phone           VARCHAR(64) NOT NULL,                    -- 手机号（仅本地存，NewAPI 无 phone 字段）
    password_enc    TEXT,                                    -- AES-GCM 加密密码密文（含 nonce，开通/拒绝/过期后擦除）
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND
    newapi_user_id  INT,                                     -- NewAPI 用户 ID（开通后经 search 回查回填）
    newapi_group    VARCHAR(64),                              -- NewAPI 分组（如 default/vip，决定可访问渠道/模型）
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),      -- 提交时间
    reviewed_by     BIGINT REFERENCES app_user(id),          -- 审核人（管理员 app_user.id）
    reviewed_at     TIMESTAMPTZ,                             -- 审核时间
    reject_reason   VARCHAR(255),                            -- 拒绝原因
    provision_error TEXT                                     -- 开通失败错误信息
);

CREATE INDEX ix_newapi_reg_invitation_id ON newapi_registration(invitation_id);
CREATE INDEX ix_newapi_reg_username ON newapi_registration(username);
CREATE INDEX ix_newapi_reg_email ON newapi_registration(email);
CREATE INDEX ix_newapi_reg_status ON newapi_registration(status);
CREATE INDEX ix_newapi_reg_status_submitted ON newapi_registration(status, submitted_at);

COMMENT ON TABLE newapi_registration IS 'NewAPI 注册申请表：凭邀请令牌公开提交，管理员审批后经 NewAPI REST 开通，5 态机 PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND';
COMMENT ON COLUMN newapi_registration.invitation_id IS '所用邀请（newapi_invitation.id）';
COMMENT ON COLUMN newapi_registration.username IS 'NewAPI 用户名（PENDING/APPROVED/FAILED 占用，REJECTED 释放）';
COMMENT ON COLUMN newapi_registration.display_name IS '显示名（传给 NewAPI display_name）';
COMMENT ON COLUMN newapi_registration.email IS '邮箱';
COMMENT ON COLUMN newapi_registration.phone IS '手机号（仅本地存，NewAPI 用户对象无 phone 字段）';
COMMENT ON COLUMN newapi_registration.password_enc IS 'AES-GCM 加密密码密文（base64-urlsafe(nonce12 + ct + tag)，开通/拒绝/pending 过期后擦除；与 nas 共用同一密钥与格式）';
COMMENT ON COLUMN newapi_registration.status IS '状态：PENDING 待审批 / APPROVED 已开通 / REJECTED 已拒绝 / FAILED 开通失败可重试 / NOT_FOUND NewAPI 用户已删';
COMMENT ON COLUMN newapi_registration.newapi_user_id IS 'NewAPI 用户 ID（开通后经 search 回查回填，建用户响应无 id）';
COMMENT ON COLUMN newapi_registration.newapi_group IS 'NewAPI 分组（如 default/vip，决定可访问渠道/模型；对应 nas 的 40/41/42 角色组）';
COMMENT ON COLUMN newapi_registration.submitted_at IS '提交时间（pending 过期扫描据此判定）';
COMMENT ON COLUMN newapi_registration.reviewed_by IS '审核人（管理员 app_user.id）';
COMMENT ON COLUMN newapi_registration.reviewed_at IS '审核时间';
COMMENT ON COLUMN newapi_registration.reject_reason IS '拒绝原因';
COMMENT ON COLUMN newapi_registration.provision_error IS '开通失败错误信息（FAILED/NOT_FOUND 重试时记录）';
