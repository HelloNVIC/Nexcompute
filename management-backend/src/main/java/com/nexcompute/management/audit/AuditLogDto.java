package com.nexcompute.management.audit;

import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.domain.AuditLogArchive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 审计记录统一 DTO（热表与归档表合并查询时映射同一结构，按 createdAt 统一排序）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogDto {
    private Long id;
    private String source; // HOT / ARCHIVE
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    private String action;
    private String targetType;
    private String targetId;
    private String content;
    private String result;
    private String errorMessage;
    private String ipAddress;
    private String operationNo;
    private String clientInfo;
    private Long mentorIdAtOp;
    private Instant createdAt;

    public static AuditLogDto fromHot(AuditLog l) {
        return AuditLogDto.builder()
                .id(l.getId()).source("HOT")
                .operatorId(l.getOperatorId()).operatorName(l.getOperatorName()).operatorRole(l.getOperatorRole())
                .action(l.getAction()).targetType(l.getTargetType()).targetId(l.getTargetId())
                .content(l.getContent()).result(l.getResult()).errorMessage(l.getErrorMessage())
                .ipAddress(l.getIpAddress()).operationNo(l.getOperationNo()).clientInfo(l.getClientInfo())
                .mentorIdAtOp(l.getMentorIdAtOp()).createdAt(l.getCreatedAt())
                .build();
    }

    public static AuditLogDto fromArchive(AuditLogArchive l) {
        return AuditLogDto.builder()
                .id(l.getId()).source("ARCHIVE")
                .operatorId(l.getOperatorId()).operatorName(l.getOperatorName()).operatorRole(l.getOperatorRole())
                .action(l.getAction()).targetType(l.getTargetType()).targetId(l.getTargetId())
                .content(l.getContent()).result(l.getResult()).errorMessage(l.getErrorMessage())
                .ipAddress(l.getIpAddress()).operationNo(l.getOperationNo()).clientInfo(l.getClientInfo())
                .mentorIdAtOp(l.getMentorIdAtOp()).createdAt(l.getCreatedAt())
                .build();
    }
}
