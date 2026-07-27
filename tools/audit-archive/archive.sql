-- =============================================================
-- Nexcompute 审计日志归档脚本（platform-audit-logging-ux D7）
-- 独立进程执行，使用独立 DB 维护凭据（非应用账号）。
-- 会话级 SET session_replication_role = replica 禁用所有触发器后：
--   1) INSERT INTO audit_log_archive SELECT 30 天前记录
--   2) DELETE FROM audit_log_audit 30 天前记录
-- 由 OS 调度（Linux cron / Windows 任务计划）按月触发，不进应用进程。
--
-- 使用：
--   psql "host=... port=5432 dbname=nexcompute user=nexcompute_dba password=..." -f archive.sql
-- 或经归档 JAR 调用。应用账号无法触发此路径（无 DISABLE TRIGGER 权限）。
-- =============================================================

\set RETENTION_DAYS 30
\set BATCH_LIMIT 100000

-- 维护角色会话级禁用触发器（仅本会话，不持久）
SET session_replication_role = replica;

BEGIN;

-- 1) 归档 30 天前记录至 audit_log_archive
INSERT INTO audit_log_archive (
    id, operator_id, operator_name, operator_role, action,
    target_type, target_id, content, result, error_message,
    ip_address, created_at, operation_no, client_info, mentor_id_at_op
)
SELECT
    id, operator_id, operator_name, operator_role, action,
    target_type, target_id, content, result, error_message,
    ip_address, created_at, operation_no, client_info, mentor_id_at_op
FROM audit_log
WHERE created_at < now() - INTERVAL '30 days'
LIMIT :BATCH_LIMIT;

-- 2) 删除已归档的热表记录（同样受 30 天 + LIMIT 约束，与上一步同 WHERE）
DELETE FROM audit_log
WHERE id IN (
    SELECT id FROM audit_log
    WHERE created_at < now() - INTERVAL '30 days'
    LIMIT :BATCH_LIMIT
);

COMMIT;

-- 恢复会话触发器（归档完成后）
RESET session_replication_role;

-- 输出本次归档量（psql 输出）
\echo 'Archive run completed.'
