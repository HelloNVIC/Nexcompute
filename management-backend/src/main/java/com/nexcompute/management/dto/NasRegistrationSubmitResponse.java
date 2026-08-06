package com.nexcompute.management.dto;

/**
 * NAS 公开注册提交结果（POST /nas-allocation/register）。
 * {@code truenasLoginUrl} 供前端成功页跳转至 TrueNAS 登录。
 */
public record NasRegistrationSubmitResponse(
        Long registrationId,
        String message,
        String truenasLoginUrl
) {
}
