package com.nexcompute.management.audit;

import lombok.Builder;
import lombok.Value;

/**
 * 审计上下文：在请求线程同步解析的操作人/导师快照/客户端信息。
 * 因 {@link AuditService#record} 为 @Async（运行于 TaskExecutor 线程，无 SecurityContext / RequestContext），
 * 故须在请求线程解析后传入，避免 operatorId/clientInfo 等落空（platform-audit-logging-ux 修复）。
 */
@Value
@Builder
public class AuditContext {
    Long operatorId;
    String operatorName;
    String operatorRole;
    Long mentorIdAtOp;
    String clientInfo;
    String ipAddress;
}
