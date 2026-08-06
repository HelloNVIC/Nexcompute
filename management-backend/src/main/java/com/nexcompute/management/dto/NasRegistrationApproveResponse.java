package com.nexcompute.management.dto;

/**
 * NAS 批准/改角色/重申上游结果。{@code webuiRole} 为 FULL_ADMIN/READONLY_ADMIN/SHARING_ADMIN 或 null（无角色）。
 */
public record NasRegistrationApproveResponse(
        String message,
        Long registrationId,
        String status,
        Integer truenasUserId,
        Integer truenasUid,
        String truenasLoginUrl,
        String webuiRole
) {
}
