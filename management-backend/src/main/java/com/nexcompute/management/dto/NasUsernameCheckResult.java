package com.nexcompute.management.dto;

/**
 * NAS 注册用户名可用性检查结果（GET /nas-allocation/register/check-username）。
 * {@code code}: AVAILABLE / INVALID / LOCAL_TAKEN / TRUENAS_TAKEN / UNREACHABLE / ERROR
 */
public record NasUsernameCheckResult(
        boolean available,
        String code,
        String message
) {
}
