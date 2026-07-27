package com.nexcompute.management.audit;

import com.nexcompute.management.domain.AuditLog;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * 全局审计拦截器（D3 丙·混合）。
 * 兜底记录所有非 GET 写操作（零遗漏），@Audited 已记的请求经 audited.done 标志让路去重（D4）。
 * 仅记通用四元组（用户/时间/方法-URL/客户端）+ operationNo + clientInfo + mentorIdAtOp 快照，
 * 不拿业务语义 action（@Audited 补）。查询（GET）不记，审计查询本身不产生审计。
 * 审计开关关闭时跳过（D12）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    public static final String ATTR_AUDITED_DONE = "audited.done";

    private final AuditPersistService auditPersistService;
    private final AuditSwitchService auditSwitchService;
    private final MentorSnapshotService mentorSnapshotService;

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        // 实际记录放在 afterCompletion 后置处理，此处空实现保留钩子
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // 仅非 GET 写操作
            String method = request.getMethod();
            if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                return;
            }
            // @Audited 已记 → 让路去重
            if (Boolean.TRUE.equals(request.getAttribute(ATTR_AUDITED_DONE))) {
                return;
            }
            // 审计开关关闭 → 停记
            if (!auditSwitchService.isAuditEnabled()) {
                return;
            }
            // 只对 Controller 方法兜底（静态资源等跳过）
            if (!(handler instanceof HandlerMethod)) {
                return;
            }

            int status = response.getStatus();
            boolean success = status >= 200 && status < 400;
            String action = method.toUpperCase() + " " + request.getRequestURI();

            AuditLog log_ = AuditLog.builder()
                    .action(action)
                    .targetType("HTTP")
                    .targetId(request.getRequestURI())
                    .content(serializeQuery(request))
                    .result(success ? "SUCCESS" : "FAILURE")
                    .errorMessage(success ? null : ("HTTP " + status))
                    .ipAddress(resolveIp(request))
                    .clientInfo(resolveClientInfo(request))
                    .operationNo(UlidGenerator.next())
                    .mentorIdAtOp(mentorSnapshotService.resolveMentorIdAtOp())
                    .build();

            try {
                UserPrincipal user = SecurityUtils.getCurrentUser();
                log_.setOperatorId(user.getId());
                log_.setOperatorName(user.getUsername());
                log_.setOperatorRole(user.getRole().name());
            } catch (Exception ignored) {
                // 未登录写操作（理论上被鉴权拦），operator 留空
            }

            persistAsync(log_);
        } catch (Exception e) {
            log.error("[Audit] 拦截器记录审计失败: {}", e.getMessage());
        }
    }

    /** 异步落盘（委托 AuditPersistService，避免本拦截器被 @Async 代理） */
    private void persistAsync(AuditLog entity) {
        auditPersistService.save(entity);
    }

    private String serializeQuery(HttpServletRequest request) {
        String q = request.getQueryString();
        return q == null ? null : ("query=" + (q.length() > 500 ? q.substring(0, 500) : q));
    }

    private String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private String resolveClientInfo(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        String ip = resolveIp(request);
        String instanceNo = request.getHeader("X-Instance-Number");
        StringBuilder sb = new StringBuilder();
        if (ua != null) sb.append("ua=").append(ua.length() > 300 ? ua.substring(0, 300) : ua);
        if (ip != null) sb.append(sb.length() > 0 ? "; " : "").append("ip=").append(ip);
        if (instanceNo != null && !instanceNo.isBlank())
            sb.append(sb.length() > 0 ? "; " : "").append("instance=").append(instanceNo);
        String info = sb.toString();
        return info.length() > 500 ? info.substring(0, 500) : info;
    }
}
