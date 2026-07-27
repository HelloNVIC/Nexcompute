package com.nexcompute.audit.archive;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Nexcompute 审计归档独立可执行单元（platform-audit-logging-ux D7 / 12.1）。
 *
 * 不进应用进程：独立 JAR，使用独立 DB 维护凭据（nexcompute_dba）。
 * 会话级 SET session_replication_role = replica 禁用触发器后：
 *   INSERT INTO audit_log_archive SELECT 30 天前 -> DELETE FROM audit_log 30 天前。
 * 由 OS 调度（cron / Windows 任务计划）按月触发。
 *
 * 应用进程完全不参与，零阻塞；归档挂了不影响应用，应用重启不影响归档调度。
 *
 * 构建：gradle :audit-archive:shadowJar（或 javac + 打包）。
 * 运行：java -jar audit-archive.jar --jdbc-url=... --db-user=nexcompute_dba --db-password=...
 */
public class AuditArchiver {

    private static final int RETENTION_DAYS = 30;
    private static final int BATCH_LIMIT = 100_000;

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);
        if (cfg.help) {
            usage();
            return;
        }
        if (cfg.jdbcUrl == null || cfg.dbUser == null || cfg.dbPassword == null) {
            System.err.println("缺少必要参数（--jdbc-url / --db-user / --db-password）");
            usage();
            System.exit(2);
        }

        Properties props = new Properties();
        props.setProperty("user", cfg.dbUser);
        props.setProperty("password", cfg.dbPassword);

        try (Connection conn = DriverManager.getConnection(cfg.jdbcUrl, props)) {
            conn.setAutoCommit(false);
            archive(conn);
        } catch (SQLException e) {
            System.err.println("[audit-archive] 归档失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        System.out.println("[audit-archive] 归档运行完成");
    }

    static void archive(Connection conn) throws SQLException {
        // 1) 会话级禁用触发器（维护角色 REPLICATION 属性允许）
        try (Statement st = conn.createStatement()) {
            st.execute("SET session_replication_role = replica");
        }

        try {
            // 2) 归档 30 天前记录
            String insertSql = "INSERT INTO audit_log_archive (" +
                    "id, operator_id, operator_name, operator_role, action, " +
                    "target_type, target_id, content, result, error_message, " +
                    "ip_address, created_at, operation_no, client_info, mentor_id_at_op" +
                    ") SELECT " +
                    "id, operator_id, operator_name, operator_role, action, " +
                    "target_type, target_id, content, result, error_message, " +
                    "ip_address, created_at, operation_no, client_info, mentor_id_at_op " +
                    "FROM audit_log WHERE created_at < now() - interval '" + RETENTION_DAYS + " days' " +
                    "LIMIT " + BATCH_LIMIT;
            int inserted;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                inserted = ps.executeUpdate();
            }
            System.out.println("[audit-archive] 归档 " + inserted + " 条");

            // 3) 删除已归档的热表记录（同 WHERE + LIMIT）
            String deleteSql = "DELETE FROM audit_log WHERE id IN (" +
                    "SELECT id FROM audit_log WHERE created_at < now() - interval '" + RETENTION_DAYS + " days' " +
                    "LIMIT " + BATCH_LIMIT + ")";
            int deleted;
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                deleted = ps.executeUpdate();
            }
            System.out.println("[audit-archive] 删除热表 " + deleted + " 条");

            conn.commit();

            // 校验两数一致（归档与删除应匹配）
            if (inserted != deleted) {
                System.out.println("[audit-archive] 注意：归档数与删除数不一致（" + inserted + " vs " + deleted
                        + "），可能存在并发写入，下次运行会补齐。");
            }
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            // 恢复会话触发器
            try (Statement st = conn.createStatement()) {
                st.execute("RESET session_replication_role");
            } catch (SQLException ignored) {
            }
        }
    }

    private static void usage() {
        System.out.println("用法: java -jar audit-archive.jar --jdbc-url=URL --db-user=USER --db-password=PWD [--help]");
    }

    static class Config {
        boolean help = false;
        String jdbcUrl;
        String dbUser;
        String dbPassword;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> c.help = true;
                    case "--jdbc-url" -> c.jdbcUrl = args[++i];
                    case "--db-user" -> c.dbUser = args[++i];
                    case "--db-password" -> c.dbPassword = args[++i];
                    default -> System.err.println("未知参数: " + args[i]);
                }
            }
            return c;
        }
    }
}
