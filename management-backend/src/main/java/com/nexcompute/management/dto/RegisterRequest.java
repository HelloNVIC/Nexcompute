package com.nexcompute.management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "注册令牌不能为空")
    private String token;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    @NotBlank(message = "工号/学号不能为空")
    private String studentId;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码至少 6 位")
    @Pattern(regexp = "^(?=.*[0-9])(?=.*[a-zA-Z]).{6,}$", message = "密码至少 6 位且需包含数字和字母")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}

