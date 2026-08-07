package com.nexcompute.management.dto;

import java.util.Map;

/**
 * NewAPI 注册申请详情（管理员）：本地行（{@link NewApiRegistrationListItem}）合并 NewAPI 实时状态。
 * NewAPI 错误在 {@code newapi.error} 而非失败请求，管理员可见本地记录（用户已删/NewAPI 不可达）。
 */
public record NewApiRegistrationDetail(
        NewApiRegistrationListItem registration,
        Map<String, Object> newapi,
        String newapiPortalUrl
) {
}
