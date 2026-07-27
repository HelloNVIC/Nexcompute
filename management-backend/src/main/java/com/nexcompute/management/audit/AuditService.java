package com.nexcompute.management.audit;

import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务。
 * operationNo(ULID) + 操作人/导师快照/客户端信息经 {@link AuditContext} 在请求线程解析后传入，
 * 避免 @Async 异步线程无 SecurityContext/RequestContext 致字段落空。
 * 审计开关关闭时跳过写库（force=true 不受开关影响恒记，D12）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuditSwitchService auditSwitchService;

    /**
     * @param ctx 请求线程解析的审计上下文（操作人/导师快照/客户端信息）
     * @param force true 时不受审计开关影响恒记（开关切换等恒审计操作，D12）
     */
    @Async
    public void record(String action, String targetType, String targetId,
                       String content, boolean success, String errorMessage,
                       AuditContext ctx, boolean force) {
        try {
            if (!force && !auditSwitchService.isAuditEnabled()) {
                return; // 审计关闭，停记新操作（已存记录仍受触发器保护）
            }

            AuditLog log_ = AuditLog.builder()
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .content(content)
                    .result(success ? "SUCCESS" : "FAILURE")
                    .errorMessage(errorMessage)
                    .operationNo(UlidGenerator.next())
                    .build();
            if (ctx != null) {
                log_.setOperatorId(ctx.getOperatorId());
                log_.setOperatorName(ctx.getOperatorName());
                log_.setOperatorRole(ctx.getOperatorRole());
                log_.setMentorIdAtOp(ctx.getMentorIdAtOp());
                log_.setClientInfo(ctx.getClientInfo());
                log_.setIpAddress(ctx.getIpAddress());
            }

            auditLogRepository.save(log_);
        } catch (Exception e) {
            log.error("[Audit] 记录审计日志失败: {}", e.getMessage());
        }
    }
}
