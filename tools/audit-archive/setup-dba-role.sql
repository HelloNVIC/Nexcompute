-- =============================================================
-- Nexcompute 审计归档维护凭据（platform-audit-logging-ux D7 / R3）
-- 创建独立 DB 维护角色 nexcompute_dba（非应用账号），
-- 仅授予 audit_log / audit_log_archive 的 SELECT/INSERT/DELETE，
-- 以及会话级 SET session_replication_role = replica 权限（禁用触发器归档用）。
-- 应用账号（nexcompute_app）无 DISABLE TRIGGER 权限，无法触发归档删除路径。
--
-- 由 DBA 一次性执行；密码从环境变量 / 密钥管理注入，勿硬编码进仓库。
-- =============================================================

-- 1) 创建维护角色（无 LOGIN 的基础角色 + 独立登录用户，演示用 nexcompute_dba 合并）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexcompute_dba') THEN
        CREATE ROLE nexcompute_dba WITH LOGIN PASSWORD :'pwd'
            NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION;
    END IF;
END $$;

-- 2) 授予归档所需的最小权限（仅审计表）
GRANT USAGE ON SCHEMA public TO nexcompute_dba;
GRANT SELECT, INSERT ON audit_log_archive TO nexcompute_dba;
GRANT SELECT, DELETE ON audit_log TO nexcompute_dba;
-- 序列（archive 表 BIGSERIAL）
GRANT USAGE, SELECT ON SEQUENCE audit_log_archive_id_seq TO nexcompute_dba;

-- 3) 会话级禁用触发器权限（归档用 replica 角色；仅授予 ALTER TABLE ... DISABLE 不必，
--    session_replication_role=replica 需要复制属性或超级用户；此处靠 REPLICATION 属性授予）
-- 注意：session_replication_role = replica 要求会话用户具备 REPLICATION 或 SUPERUSER。
-- 上一步已建角色带 REPLICATION 属性，故 nexcompute_dba 可在会话级设 replica 角色。

\echo 'Created nexcompute_dba role with minimal archive privileges.'

-- 校验：应用账号应无 DELETE 权限（确认应用账号无法触发归档删除）
-- SELECT has_table_privilege('nexcompute_app', 'audit_log', 'DELETE');  -- 期望 false
