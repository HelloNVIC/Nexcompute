package com.nexcompute.management.audit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 审计不可删改触发器验证（platform-audit-logging-ux 1.5 / 13.1）。
 * 直连运行中的 PostgreSQL（系统已启动，V27 迁移已应用），断言应用账号对 audit_log 的
 * UPDATE / DELETE 被 BEFORE 触发器 RAISE 拒绝。
 *
 * 不可达时跳过（Assumptions）；不留数据（INSERT + 改删在同一事务，触发器 RAISE 致整体回滚）。
 * 连接参数可由系统属性覆盖：-Ddb.url / -Ddb.user / -Ddb.password
 */
class AuditTriggerImmutabilityTest {

    private static final String URL = System.getProperty("db.url", "jdbc:postgresql://localhost:5432/nexcompute");
    private static final String USER = System.getProperty("db.user", "nexcompute");
    private static final String PASS = System.getProperty("db.password", "nexcompute");

    private boolean dbReachable() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            return c.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    @Test
    void triggersExist() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过触发器验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT tgname FROM pg_trigger WHERE tgrelid='audit_log'::regclass AND NOT tgisinternal")) {
                java.util.Set<String> names = new java.util.HashSet<>();
                while (rs.next()) names.add(rs.getString(1));
                assertThat(names).contains("trg_audit_no_update", "trg_audit_no_delete");
            }
        }
    }

    @Test
    void updateOnAuditLogIsRejected() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过触发器验证");
        Connection c = DriverManager.getConnection(URL, USER, PASS);
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO audit_log (action, result, created_at, operation_no) " +
                    "VALUES ('TEST_TRIGGER_UPDATE', 'SUCCESS', now(), '01TRGUPD')");
            long id;
            try (ResultSet rs = st.executeQuery("SELECT max(id) FROM audit_log WHERE operation_no='01TRGUPD'")) {
                rs.next();
                id = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE audit_log SET result='FAILURE' WHERE id=?")) {
                ps.setLong(1, id);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("不可修改或删除");
            }
        } finally {
            c.rollback(); // 触发器 RAISE 致事务回滚，INSERT 亦回滚，不留数据
            c.close();
        }
    }

    @Test
    void deleteOnAuditLogIsRejected() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过触发器验证");
        Connection c = DriverManager.getConnection(URL, USER, PASS);
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO audit_log (action, result, created_at, operation_no) " +
                    "VALUES ('TEST_TRIGGER_DELETE', 'SUCCESS', now(), '01TRGDEL')");
            long id;
            try (ResultSet rs = st.executeQuery("SELECT max(id) FROM audit_log WHERE operation_no='01TRGDEL'")) {
                rs.next();
                id = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM audit_log WHERE id=?")) {
                ps.setLong(1, id);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("不可修改或删除");
            }
        } finally {
            c.rollback();
            c.close();
        }
    }

    @Test
    void archiveTableAndColumnsExist() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过触发器验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name='audit_log_archive' " +
                    "AND column_name IN ('mentor_id_at_op','operation_no','client_info')")) {
                java.util.Set<String> cols = new java.util.HashSet<>();
                while (rs.next()) cols.add(rs.getString(1));
                assertThat(cols).contains("mentor_id_at_op", "operation_no", "client_info");
            }
        }
    }

    @Test
    void agentUpgradeTaskProgressColumnsExist() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过触发器验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name='agent_upgrade_task' " +
                    "AND column_name IN ('progress_stage','stage_percents')")) {
                java.util.Set<String> cols = new java.util.HashSet<>();
                while (rs.next()) cols.add(rs.getString(1));
                assertThat(cols).contains("progress_stage", "stage_percents");
            }
        }
    }
}
