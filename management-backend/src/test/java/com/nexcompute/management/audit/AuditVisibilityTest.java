package com.nexcompute.management.audit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 审计三角色可见性验证（platform-audit-logging-ux 2.7 / 13.2）。
 * 直连运行中 PG，插入模拟审计行后用导师/学生/管理员可见性 WHERE 查询，验证 mentorIdAtOp 快照语义：
 *   导师 = operatorId=me OR (role=STUDENT AND mentor_id_at_op=me)
 *   学生 = operatorId=me
 *   管理员 = 全表
 * 全程事务内插入 + 回滚（rollback 不触发 DELETE 触发器），不留测试数据。
 */
class AuditVisibilityTest {

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

    private void insertRow(Statement st, long opId, String role, String action, Long mentorIdAtOp) throws SQLException {
        String mentor = mentorIdAtOp == null ? "NULL" : String.valueOf(mentorIdAtOp);
        st.execute("INSERT INTO audit_log (operator_id, operator_role, action, result, created_at, operation_no, mentor_id_at_op) " +
                "VALUES (" + opId + ", '" + role + "', '" + action + "', 'SUCCESS', now(), 'VIS" + opId + "', " + mentor + ")");
    }

    @Test
    void mentorSeesOwnAndStudentsBySnapshot() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过可见性验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // 导师 me=100；其学生 201；他人学生 202（导师 999）
                insertRow(st, 100L, "MENTOR", "MENTOR_OWN", null);
                insertRow(st, 201L, "STUDENT", "STUDENT_OF_100", 100L);
                insertRow(st, 202L, "STUDENT", "STUDENT_OF_999", 999L);

                long me = 100L;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT action FROM audit_log WHERE operator_id = ? " +
                        "OR (operator_role = 'STUDENT' AND mentor_id_at_op = ?) " +
                        "AND operation_no LIKE 'VIS%' ORDER BY action")) {
                    ps.setLong(1, me);
                    ps.setLong(2, me);
                    try (ResultSet rs = ps.executeQuery()) {
                        java.util.Set<String> actions = new java.util.HashSet<>();
                        while (rs.next()) actions.add(rs.getString(1));
                        assertThat(actions).containsExactlyInAnyOrder("MENTOR_OWN", "STUDENT_OF_100");
                        assertThat(actions).doesNotContain("STUDENT_OF_999");
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void studentSeesOnlyOwn() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过可见性验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertRow(st, 301L, "STUDENT", "MINE", null);
                insertRow(st, 302L, "STUDENT", "OTHERS", null);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT action FROM audit_log WHERE operator_id = ? AND operation_no LIKE 'VIS%'")) {
                    ps.setLong(1, 301L);
                    try (ResultSet rs = ps.executeQuery()) {
                        java.util.Set<String> actions = new java.util.HashSet<>();
                        while (rs.next()) actions.add(rs.getString(1));
                        assertThat(actions).containsExactly("MINE");
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void adminSeesAllMarkedRows() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过可见性验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertRow(st, 401L, "ADMIN", "ADMIN_ACT", null);
                insertRow(st, 402L, "MENTOR", "MENTOR_ACT", null);
                insertRow(st, 403L, "STUDENT", "STUDENT_ACT", 402L);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT action FROM audit_log WHERE operation_no LIKE 'VIS%' ORDER BY action")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        java.util.Set<String> actions = new java.util.HashSet<>();
                        while (rs.next()) actions.add(rs.getString(1));
                        assertThat(actions).contains("ADMIN_ACT", "MENTOR_ACT", "STUDENT_ACT");
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }
}
