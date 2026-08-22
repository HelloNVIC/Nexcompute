package com.nexcompute.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户自助修改密码请求（password-management-and-id-validation D7）。
 * 新密码强度规则与注册一致：≥6 位且含字母与数字。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "密码至少 6 位")
    @Pattern(regexp = "^(?=.*[0-9])(?=.*[a-zA-Z]).{6,}$", message = "密码至少 6 位且需包含数字和字母")
    private String newPassword;
}
