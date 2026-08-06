package com.nexcompute.management.dto;

import java.time.Instant;

/**
 * NAS 邀请响应（nas-allocation）：实体字段 + 派生剩余次数 + 完整注册链接。
 */
public record NasInvitationResponse(
        Long id,
        String token,
        String label,
        Integer maxUses,
        Integer usedCount,
        Integer remaining,
        Instant expiresAt,
        Instant createdAt,
        Instant revokedAt,
        String registerUrl,
        boolean valid
) {
}
