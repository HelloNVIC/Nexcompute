package com.nexcompute.management.audit;

import com.nexcompute.management.domain.UserRole;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计查询条件（热表与归档表共用同一 WHERE，跨边界合并查询时两表同 spec）。
 * 含：时间范围、操作人（ID/姓名/角色）、操作类型、目标类型/ID、结果、客户端、
 * mentorIdAtOp、operationNo 精确、content/errorMessage/operatorName/targetId/clientInfo LIKE。
 * 角色可见性：管理员=全表；导师=自己 OR (operatorRole=STUDENT AND mentorIdAtOp=me)；学生=自己。
 */
@Data
@Builder
public class AuditQueryCriteria {

    /** 角色可见性过滤；为 null 表示不附加可见性（如管理员） */
    private UserRole viewerRole;
    private Long viewerId;

    private Instant start;
    private Instant end;
    private Long operatorId;
    private String operatorName;   // LIKE
    private String operatorRole;   // 精确
    private String action;         // 精确
    private String actionLike;     // 模糊（可选，便于前端统一）
    private String targetType;     // 精确
    private String targetId;       // LIKE
    private String result;         // 精确 SUCCESS/FAILURE
    private String clientInfo;     // LIKE
    private Long mentorIdAtOp;     // 精确
    private String operationNo;    // 精确
    private String keyword;        // 跨字段模糊（content/errorMessage/operatorName/targetId/clientInfo）

    /** 构建可见性 predicate */
    private static <T> Predicate visibility(Specification<T> unused, jakarta.persistence.criteria.Root<T> root, CriteriaBuilder cb, UserRole role, Long me) {
        if (role == null) return cb.conjunction();
        switch (role) {
            case ADMIN:
                return cb.conjunction();
            case MENTOR:
                // operatorId = me OR (operatorRole = 'STUDENT' AND mentorIdAtOp = me)
                Predicate self = cb.equal(root.get("operatorId"), me);
                Predicate studentOfMine = cb.and(
                        cb.equal(root.get("operatorRole"), "STUDENT"),
                        cb.equal(root.get("mentorIdAtOp"), me));
                return cb.or(self, studentOfMine);
            case STUDENT:
            default:
                return cb.equal(root.get("operatorId"), me);
        }
    }

    private static Predicate like(CriteriaBuilder cb, Expression<String> path, String val) {
        if (val == null || val.isBlank()) return null;
        return cb.like(cb.lower(path), "%" + val.toLowerCase() + "%");
    }

    private static Predicate eq(CriteriaBuilder cb, Expression<String> path, String val) {
        if (val == null || val.isBlank()) return null;
        return cb.equal(path, val);
    }

    /**
     * 构建 Specification（泛型，热表/归档表共用）。
     * 由于 Specification 是泛型的，这里返回 Specification&lt;Object&gt; 不便直接传 repository，
     * 故改为提供静态 build 方法由调用方构造具体泛型 Specification（见下方工厂方法）。
     */
    public static <T> Specification<T> specOf(AuditQueryCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(visibility(null, root, cb, c.viewerRole, c.viewerId));

            if (c.start != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), c.start));
            if (c.end != null) ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), c.end));
            if (c.operatorId != null) ps.add(cb.equal(root.get("operatorId"), c.operatorId));
            add(ps, like(cb, root.get("operatorName"), c.operatorName));
            add(ps, eq(cb, root.get("operatorRole"), c.operatorRole));
            add(ps, eq(cb, root.get("action"), c.action));
            add(ps, like(cb, root.get("action"), c.actionLike));
            add(ps, eq(cb, root.get("targetType"), c.targetType));
            add(ps, like(cb, root.get("targetId"), c.targetId));
            add(ps, eq(cb, root.get("result"), c.result));
            add(ps, like(cb, root.get("clientInfo"), c.clientInfo));
            if (c.mentorIdAtOp != null) ps.add(cb.equal(root.get("mentorIdAtOp"), c.mentorIdAtOp));
            add(ps, eq(cb, root.get("operationNo"), c.operationNo));

            if (c.keyword != null && !c.keyword.isBlank()) {
                String k = "%" + c.keyword.toLowerCase() + "%";
                Predicate contentLike = cb.like(cb.lower(root.get("content")), k);
                Predicate errLike = cb.like(cb.lower(root.get("errorMessage")), k);
                Predicate nameLike = cb.like(cb.lower(root.get("operatorName")), k);
                Predicate tidLike = cb.like(cb.lower(root.get("targetId")), k);
                Predicate ciLike = cb.like(cb.lower(root.get("clientInfo")), k);
                ps.add(cb.or(contentLike, errLike, nameLike, tidLike, ciLike));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static void add(List<Predicate> ps, Predicate p) {
        if (p != null) ps.add(p);
    }
}
