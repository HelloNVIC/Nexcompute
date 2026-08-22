package com.nexcompute.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 忘记密码-发送验证码请求（password-management-and-id-validation D4）。
 * 凭工号/学号请求发送验证码；无论账号是否存在均返回统一成功消息（防枚举）。
 */
@Data
public class ForgotPasswordSendCodeRequest {

    @NotBlank(message = "工号/学号不能为空")
    private String username;
}
