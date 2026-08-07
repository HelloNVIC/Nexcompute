package com.nexcompute.management.dto;

import java.time.Instant;

/**
 * NewAPI 公开注册表单元数据（GET /newapi-allocation/register?token=）：校验 token 后返回 label/剩余/过期。
 * 不消耗名额。
 */
public record NewApiRegisterFormMeta(
        String token,
        boolean valid,
        String label,
        Integer remaining,
        Instant expiresAt
) {
}
