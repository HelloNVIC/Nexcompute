package com.nexcompute.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 注册链接校验结果（GET /auth/register/validate）。
 * 学生 / 导师 / 管理员邀请链接共用 RegistrationLink，凭 linkType 区分。
 * valid=true 时 remaining/expiresAt 有值；valid=false 时 reason 给出具体失效原因（作废/用尽/过期/不存在/类型不匹配）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationLinkValidateResult {
    private boolean valid;
    private String linkType;
    private Integer remaining;
    private Instant expiresAt;
    private String reason;
}
