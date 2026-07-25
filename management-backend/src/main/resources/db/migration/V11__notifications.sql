-- V11 公告与通知系统（任务 13.1）
-- 公告（标题、内容、定向范围、定向目标、发布方式、发布时间、状态）
-- 通知消息（用户、类型、引用ID、内容、已读状态、时间，永久保留）

-- 公告
CREATE TABLE announcement (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    content         TEXT NOT NULL,
    target_scope    VARCHAR(20) NOT NULL,            -- ALL / GROUP / ROLE
    target_id       BIGINT,                          -- 定向目标 ID（组 ID 或角色 ID）
    target_role     VARCHAR(20),                     -- 当 target_scope=ROLE 时的角色
    publish_mode    VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE', -- IMMEDIATE / SCHEDULED
    publish_at      TIMESTAMPTZ,                     -- 定时发布时间
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / PUBLISHED
    author_id       BIGINT NOT NULL REFERENCES app_user(id),
    author_name     VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_announcement_status ON announcement(status);
CREATE INDEX idx_announcement_publish_at ON announcement(publish_at);
CREATE TRIGGER trg_announcement_updated_at BEFORE UPDATE ON announcement
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 通知消息（永久保留，含已读）
CREATE TABLE notification_message (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    type            VARCHAR(30) NOT NULL,            -- CONTAINER / STORAGE_POOL / TICKET / ANNOUNCEMENT
    ref_id          BIGINT,                          -- 引用 ID（容器/存储池/工单/公告）
    title           VARCHAR(200),
    content         TEXT NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ
);
CREATE INDEX idx_notification_user_read ON notification_message(user_id, is_read);
CREATE INDEX idx_notification_user_created ON notification_message(user_id, created_at DESC);
CREATE INDEX idx_notification_type ON notification_message(type);
