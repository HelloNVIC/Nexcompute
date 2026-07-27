-- V27 platform-audit-logging-ux：操作审计不可删改 + 字段补全 + 归档表 + OTA 进度阶段
-- 1.1 audit_log 加 operation_no/client_info/mentor_id_at_op（均 nullable，存量回填 null）
-- 1.2 audit_log_archive 镜像表（含 mentor_id_at_op，含索引）
-- 1.3 BEFORE UPDATE/BEFORE DELETE 触发器 RAISE EXCEPTION 锁应用账号改删
-- 1.4 agent_upgrade_task 加 progress_stage / stage_percents
-- 2.12 补索引 mentor_id_at_op/result/target_type/operation_no（热表+归档表）

-- 1.1 audit_log 字段补全
ALTER TABLE audit_log ADD COLUMN operation_no    VARCHAR(26);
ALTER TABLE audit_log ADD COLUMN client_info    VARCHAR(500);
ALTER TABLE audit_log ADD COLUMN mentor_id_at_op BIGINT;
COMMENT ON COLUMN audit_log.operation_no     IS '操作记录唯一编码（ULID 有序短码）';
COMMENT ON COLUMN audit_log.client_info      IS '客户端信息（UA + IP，受控端含实例编号）';
COMMENT ON COLUMN audit_log.mentor_id_at_op  IS '操作时导师归属快照（学生记当时导师 ID，导师/管理员为 null）';

-- 1.2 audit_log_archive 归档表（结构镜像 audit_log 含 mentor_id_at_op）
CREATE TABLE IF NOT EXISTS audit_log_archive (
    id              BIGSERIAL PRIMARY KEY,
    operator_id     BIGINT,
    operator_name   VARCHAR(100),
    operator_role   VARCHAR(20),
    action          VARCHAR(100) NOT NULL,
    target_type     VARCHAR(50),
    target_id       VARCHAR(100),
    content         TEXT,
    result          VARCHAR(20) NOT NULL,
    error_message   TEXT,
    ip_address      VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    operation_no    VARCHAR(26),
    client_info     VARCHAR(500),
    mentor_id_at_op BIGINT
);
COMMENT ON TABLE audit_log_archive IS '审计归档表（镜像 audit_log，30 天前记录每月归档，D7）';

-- 1.3 不可删改：BEFORE UPDATE / BEFORE DELETE 触发器
CREATE OR REPLACE FUNCTION reject_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log 不可修改或删除（合规约束：触发器拦截）';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_no_update ON audit_log;
CREATE TRIGGER trg_audit_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_audit_no_delete ON audit_log;
CREATE TRIGGER trg_audit_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- 同样为归档表加保护（归档表同样不可改删，归档走禁用触发器运维路径）
DROP TRIGGER IF EXISTS trg_audit_archive_no_update ON audit_log_archive;
CREATE TRIGGER trg_audit_archive_no_update
    BEFORE UPDATE ON audit_log_archive
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_audit_archive_no_delete ON audit_log_archive;
CREATE TRIGGER trg_audit_archive_no_delete
    BEFORE DELETE ON audit_log_archive
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- 1.4 agent_upgrade_task 进度阶段列
ALTER TABLE agent_upgrade_task ADD COLUMN progress_stage  VARCHAR(20);
ALTER TABLE agent_upgrade_task ADD COLUMN stage_percents  TEXT;   -- JSON: {"downloading":45,"verifying":0,"backing_up":0,"replacing":0,"waiting":0}
COMMENT ON COLUMN agent_upgrade_task.progress_stage IS '当前升级阶段：downloading/verifying/backing_up/replacing/waiting';
COMMENT ON COLUMN agent_upgrade_task.stage_percents  IS '各段百分比 JSON（0-100）';

-- 2.12 补索引（热表）
CREATE INDEX IF NOT EXISTS idx_audit_log_mentor_id_at_op ON audit_log(mentor_id_at_op);
CREATE INDEX IF NOT EXISTS idx_audit_log_result           ON audit_log(result);
CREATE INDEX IF NOT EXISTS idx_audit_log_target_type      ON audit_log(target_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_operation_no      ON audit_log(operation_no);

-- 2.12 归档表索引
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_operator       ON audit_log_archive(operator_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_created_at     ON audit_log_archive(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_action         ON audit_log_archive(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_mentor_id_at_op ON audit_log_archive(mentor_id_at_op);
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_result         ON audit_log_archive(result);
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_target_type    ON audit_log_archive(target_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_archive_operation_no    ON audit_log_archive(operation_no);

-- 2.8 系统配置表（存 audit.enabled 等开关；归档与开关 D12）
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value TEXT NOT NULL,
    description  VARCHAR(255),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE system_config IS '系统配置键值表（审计开关等）';

INSERT INTO system_config (config_key, config_value, description)
VALUES ('audit.enabled', 'true', '审计开关：true 记录，false 停记（开关切换恒审计）')
ON CONFLICT (config_key) DO NOTHING;
