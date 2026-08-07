package com.nexcompute.management.dto;

/**
 * NewAPI 批准/改分组/重申上游结果。{@code group} 为 NewAPI 分组（如 default/vip）或 null（无分组）。
 */
public record NewApiRegistrationApproveResponse(
        String message,
        Long registrationId,
        String status,
        Integer newapiUserId,
        String newapiPortalUrl,
        String group
) {
}
