package com.nexcompute.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工号/学号可用性校验结果（GET /auth/register/check-username，password-management-and-id-validation D5）。
 * available=false 且 reason="邀请链接无效" 表示邀请令牌失效（不反馈存在性，防开放枚举）；
 * 邀请有效时 available=是否存在性查重结果（true=可注册，false=已被占用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsernameAvailabilityResult {
    private boolean available;
    private String reason;
}
