package com.nexcompute.management.audit;

import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计日志异步落盘服务（platform-audit-logging-ux D3）。
 * 独立 bean 承载 @Async，使 {@link AuditInterceptor} 本身不被代理、可被 WebConfig 以具体类型注入
 * （拦截器实现 HandlerInterceptor 接口，若其自身带 @Async 会被 JDK 动态代理，无法按具体类型注入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPersistService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void save(AuditLog entity) {
        try {
            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.error("[Audit] 异步写库失败: {}", e.getMessage());
        }
    }
}
