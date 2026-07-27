package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 审计归档表实体（镜像 {@link AuditLog}，含 mentorIdAtOp 快照）。
 * 30 天前记录每月归档至此；导师跨表历史查询按 mentorIdAtOp 过滤。
 * 归档表同样受 BEFORE UPDATE/DELETE 触发器保护，归档走禁用触发器运维路径。
 */
@Entity
@Table(name = "audit_log_archive")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogArchive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 100)
    private String operatorName;

    @Column(name = "operator_role", length = 20)
    private String operatorRole;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 100)
    private String targetId;

    private String content;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "operation_no", length = 26)
    private String operationNo;

    @Column(name = "client_info", length = 500)
    private String clientInfo;

    @Column(name = "mentor_id_at_op")
    private Long mentorIdAtOp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
