package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

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
    private String result; // SUCCESS / FAILURE

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /** 操作记录唯一编码（ULID 有序短码） */
    @Column(name = "operation_no", length = 26)
    private String operationNo;

    /** 客户端信息（UA + IP，受控端操作时含实例编号） */
    @Column(name = "client_info", length = 500)
    private String clientInfo;

    /** 操作时导师归属快照（学生记当时导师 ID，导师/管理员为 null） */
    @Column(name = "mentor_id_at_op")
    private Long mentorIdAtOp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
