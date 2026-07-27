package com.nexcompute.management.audit;

import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计上下文解析器：在请求线程同步解析操作人 / 导师快照 / 客户端信息 / IP。
 * 供 {@link AuditAspect}（请求线程）调用，解析后传入 @Async 的 {@link AuditService#record}，
 * 避免 SecurityContext/RequestContext 在异步线程丢失致 operatorId 等落空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditContextResolver {

    private final MentorSnapshotService mentorSnapshotService;

    public AuditContext resolve() {
        AuditContext.AuditContextBuilder b = AuditContext.builder();

        try {
            UserPrincipal user = SecurityUtils.getCurrentUser();
            b.operatorId(user.getId())
                    .operatorName(user.getUsername())
                    .operatorRole(user.getRole().name());
        } catch (Exception ignored) {
            // 无登录上下文（如受控端回调）
        }

        try {
            b.mentorIdAtOp(mentorSnapshotService.resolveMentorIdAtOp());
        } catch (Exception e) {
            log.debug("[Audit] 解析 mentorIdAtOp 失败: {}", e.getMessage());
        }

        HttpServletRequest request = currentRequest();
        if (request != null) {
            String ip = resolveIp(request);
            b.ipAddress(ip);
            b.clientInfo(buildClientInfo(request, ip));
        }
        return b.build();
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private String buildClientInfo(HttpServletRequest request, String ip) {
        String ua = request.getHeader("User-Agent");
        // 受控端操作时含实例编号（受控端请求带 X-Instance-Number 头）
        String instanceNo = request.getHeader("X-Instance-Number");
        StringBuilder sb = new StringBuilder();
        if (ua != null) sb.append("ua=").append(truncate(ua, 300));
        if (ip != null) sb.append(sb.length() > 0 ? "; " : "").append("ip=").append(ip);
        if (instanceNo != null && !instanceNo.isBlank())
            sb.append(sb.length() > 0 ? "; " : "").append("instance=").append(instanceNo);
        String info = sb.toString();
        return info.length() > 500 ? info.substring(0, 500) : info;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
