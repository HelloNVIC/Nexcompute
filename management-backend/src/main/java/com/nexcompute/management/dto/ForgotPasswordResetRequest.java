package com.nexcompute.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 忘记密码-重置请求（password-management-and-id-validation D7）。
 * 凭工号/学号 + 验证码 + 新密码完成重置；新密码强度规则与注册一致。
 */
@Data
public class ForgotPasswordResetRequest {

    @NotBlank(message = "工号/学号不能为空")
    private String username;

    @NotBlank(message = "验证码不能为空")
    private String code;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "密码至少 6 位")
    @Pattern(regexp = "^(?=.*[0-9])(?=.*[a-zA-Z]).{6,}$", message = "密码至少 6 位且需包含数字和字母")
    private String newPassword;
}
