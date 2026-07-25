package com.nexcompute.management.audit;

import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.repository.AuditLogRepository;
import com.nexcompute.management.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志服务（任务 2.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void record(String action, String targetType, String targetId,
                       String content, boolean success, String errorMessage) {
        try {
            AuditLog.AuditLogBuilder builder = AuditLog.builder()
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .content(content)
                    .result(success ? "SUCCESS" : "FAILURE")
                    .errorMessage(errorMessage);

            try {
                var user = SecurityUtils.getCurrentUser();
                builder.operatorId(user.getId())
                        .operatorName(user.getUsername())
                        .operatorRole(user.getRole().name());
            } catch (Exception ignored) {
                // 无登录上下文（如受控端回调），operator 留空
            }

            builder.ipAddress(resolveClientIp());

            auditLogRepository.save(builder.build());
        } catch (Exception e) {
            log.error("[Audit] 记录审计日志失败: {}", e.getMessage());
        }
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) {
                ip = request.getRemoteAddr();
            }
            return ip;
        } catch (Exception e) {
            return null;
        }
    }
}
