package com.nexcompute.management.security;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：获取当前登录用户
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static UserPrincipal getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return (UserPrincipal) auth.getPrincipal();
    }

    public static Long getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public static UserRole getCurrentRole() {
        return getCurrentUser().getRole();
    }

    public static boolean isAdmin() {
        try {
            return getCurrentRole() == UserRole.ADMIN;
        } catch (BusinessException e) {
            return false;
        }
    }

    public static boolean isMentor() {
        try {
            return getCurrentRole() == UserRole.MENTOR;
        } catch (BusinessException e) {
            return false;
        }
    }
}
