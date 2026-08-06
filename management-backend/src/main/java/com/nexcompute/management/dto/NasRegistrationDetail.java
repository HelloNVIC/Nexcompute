package com.nexcompute.management.dto;

import java.util.Map;

/**
 * NAS 注册申请详情（管理员）：本地行（{@link NasRegistrationListItem}）合并 TrueNAS 实时状态。
 * TrueNAS 错误在 {@code truenas.error} 而非失败请求，管理员可见本地记录（用户已删/TrueNAS 不可达）。
 */
public record NasRegistrationDetail(
        NasRegistrationListItem registration,
        Map<String, Object> truenas,
        String truenasLoginUrl
) {
}
