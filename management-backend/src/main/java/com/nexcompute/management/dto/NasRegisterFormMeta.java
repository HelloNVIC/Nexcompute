package com.nexcompute.management.dto;

import java.time.Instant;

/**
 * NAS 公开注册表单元数据（GET /nas-allocation/register?token=）：校验 token 后返回 label/剩余/过期。
 * 不消耗名额。
 */
public record NasRegisterFormMeta(
        String token,
        boolean valid,
        String label,
        Integer remaining,
        Instant expiresAt
) {
}
