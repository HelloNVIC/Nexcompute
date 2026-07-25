package com.nexcompute.management.security;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限鉴权拦截器（任务 2.4）
 * 按 @RequirePermission 注解校验当前用户角色对模块的操作权限。
 */
@Component
@RequiredArgsConstructor
public class PermissionAuthorizationInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        RequirePermission annotation = method.getMethodAnnotation(RequirePermission.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (annotation == null) {
            return true;
        }

        UserPrincipal user;
        try {
            user = SecurityUtils.getCurrentUser();
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 管理员默认放行（管理员拥有全部权限）
        if (user.getRole() == UserRole.ADMIN) {
            return true;
        }

        String action = annotation.action().name().toLowerCase();
        boolean allowed = permissionService.hasPermission(user.getRole(), annotation.module(), action);
        if (!allowed) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED,
                    String.format("无权限执行此操作：%s:%s", annotation.module(), action));
        }
        return true;
    }
}
