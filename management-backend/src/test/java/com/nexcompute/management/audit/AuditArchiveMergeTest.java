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
 * 审计归档跨边界合并查询验证（platform-audit-logging-ux 13.3）。
 * 直连运行中 PG，事务内分别向热表 audit_log 与归档表 audit_log_archive 插入不同日期记录，
 * 用 UNION ALL 合并两表（同 WHERE）并按 createdAt 排序，验证跨边界查询返回两表结果。
 * 事务回滚不留数据（archive 表无 DELETE 触发器，热表触发器不拦 INSERT/事务回滚）。
 */
class AuditArchiveMergeTest {

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
    void crossBoundaryUnionReturnsBothTables() throws SQLException {
        assumeTrue(dbReachable(), "运行中 PostgreSQL 不可达，跳过归档合并验证");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // 热表：近期记录；归档表：30 天前记录（同一操作人，便于合并过滤）
                st.execute("INSERT INTO audit_log (operator_id, operator_role, action, result, created_at, operation_no, mentor_id_at_op) " +
                        "VALUES (701, 'STUDENT', 'MERGE_HOT', 'SUCCESS', now(), 'MRGHOT', 700)");
                st.execute("INSERT INTO audit_log_archive (operator_id, operator_role, action, result, created_at, operation_no, mentor_id_at_op) " +
                        "VALUES (701, 'STUDENT', 'MERGE_ARCHIVE', 'SUCCESS', now() - interval '60 days', 'MRGARCH', 700)");

                // 合并查询：导师 700 视角（operatorId=me OR (STUDENT AND mentorIdAtOp=me)），UNION 两表
                String sql = "SELECT action, operation_no FROM (" +
                        "  SELECT action, operation_no, created_at, operator_id, operator_role, mentor_id_at_op FROM audit_log " +
                        "  UNION ALL " +
                        "  SELECT action, operation_no, created_at, operator_id, operator_role, mentor_id_at_op FROM audit_log_archive" +
                        ") merged WHERE operator_id = 701 OR (operator_role = 'STUDENT' AND mentor_id_at_op = 700) " +
                        "AND operation_no LIKE 'MRG%' ORDER BY created_at";
                try (PreparedStatement ps = c.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    java.util.List<String> actions = new java.util.ArrayList<>();
                    while (rs.next()) actions.add(rs.getString(1));
                    // 归档（60 天前）在前，热表（now）在后
                    assertThat(actions).containsExactly("MERGE_ARCHIVE", "MERGE_HOT");
                }
            } finally {
                c.rollback();
            }
        }
    }
}
