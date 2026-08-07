package com.nexcompute.management.dto;

/**
 * NewAPI 注册用户名可用性检查结果（GET /newapi-allocation/register/check-username）。
 * {@code code}: AVAILABLE / INVALID / LOCAL_TAKEN / NEWAPI_TAKEN / UNREACHABLE / ERROR
 */
public record NewApiUsernameCheckResult(
        boolean available,
        String code,
        String message
) {
}
