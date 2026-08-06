package com.nexcompute.management.dto;

import java.time.Instant;

/**
 * NAS 注册申请列表项（管理员审批/已开通列表）。不含 password_enc（敏感）。
 * {@code hasPassword} 标记是否暂存了密码（PENDING/FAILED=true；APPROVED/REJECTED/NOT_FOUND=false）。
 */
public record NasRegistrationListItem(
        Long id,
        Long invitationId,
        String username,
        String fullName,
        String email,
        String phone,
        String status,
        Integer truenasUserId,
        Integer truenasUid,
        Instant submittedAt,
        Long reviewedBy,
        Instant reviewedAt,
        String rejectReason,
        String provisionError,
        boolean hasPassword
) {
}
