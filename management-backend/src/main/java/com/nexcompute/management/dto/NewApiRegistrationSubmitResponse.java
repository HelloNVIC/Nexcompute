package com.nexcompute.management.dto;

/**
 * NewAPI 公开注册提交结果（POST /newapi-allocation/register）。
 * {@code newapiPortalUrl} 供前端成功页提示前往 NewUI（NewAPI 网关）登录。
 */
public record NewApiRegistrationSubmitResponse(
        Long registrationId,
        String message,
        String newapiPortalUrl
) {
}
