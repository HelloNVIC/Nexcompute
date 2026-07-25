-- V10 工单系统（任务 12.1）
-- 工单：提交人、课题组、类型[资源申请/故障报告/特殊配置/权限申请/镜像申请]、内容、状态[待处理/已关闭]、回复内容、流转历史

CREATE TABLE ticket (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    type            VARCHAR(30) NOT NULL,            -- RESOURCE / FAULT / SPECIAL_CONFIG / PERMISSION / IMAGE
    content         TEXT NOT NULL,
    submitter_id    BIGINT NOT NULL REFERENCES app_user(id),
    submitter_name  VARCHAR(100) NOT NULL,
    group_id        BIGINT REFERENCES research_group(id),
    group_name      VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / CLOSED
    reply           TEXT,                            -- 管理员回复内容
    replier_id      BIGINT REFERENCES app_user(id),
    replier_name    VARCHAR(100),
    replied_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_submitter ON ticket(submitter_id);
CREATE INDEX idx_ticket_group ON ticket(group_id);
CREATE INDEX idx_ticket_status ON ticket(status);
CREATE INDEX idx_ticket_type ON ticket(type);
CREATE TRIGGER trg_ticket_updated_at BEFORE UPDATE ON ticket
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 工单流转历史
CREATE TABLE ticket_history (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES ticket(id),
    action          VARCHAR(50) NOT NULL,            -- CREATED / REPLIED / CLOSED
    operator_id     BIGINT NOT NULL,
    operator_name   VARCHAR(100),
    content         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_history_ticket ON ticket_history(ticket_id);
