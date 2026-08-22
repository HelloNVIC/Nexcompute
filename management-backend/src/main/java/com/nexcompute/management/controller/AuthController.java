package com.nexcompute.management.controller;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.dto.ChangePasswordRequest;
import com.nexcompute.management.dto.ForgotPasswordResetRequest;
import com.nexcompute.management.dto.ForgotPasswordSendCodeRequest;
import com.nexcompute.management.dto.LoginRequest;
import com.nexcompute.management.dto.LoginResponse;
import com.nexcompute.management.dto.MentorRegisterRequest;
import com.nexcompute.management.dto.RegisterRequest;
import com.nexcompute.management.dto.RegistrationLinkValidateResult;
import com.nexcompute.management.dto.UpdateProfileRequest;
import com.nexcompute.management.dto.UserInfoDto;
import com.nexcompute.management.dto.UsernameAvailabilityResult;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口（任务 2.2、3.5）
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<UserInfoDto> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /** 导师邀请注册（凭管理员发放的导师邀请令牌注册并建组） */
    @PostMapping("/mentor-register")
    public ApiResponse<UserInfoDto> mentorRegister(@Valid @RequestBody MentorRegisterRequest request) {
        return ApiResponse.success(authService.registerMentor(request));
    }

    /** 管理员邀请注册（凭管理员发放的管理员邀请令牌注册为管理员，表单复用学生注册字段） */
    @PostMapping("/admin-register")
    public ApiResponse<UserInfoDto> adminRegister(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.registerAdmin(request));
    }

    /**
     * 校验注册链接有效性（公开，不消耗名额）。
     * 学生 / 导师 / 管理员邀请链接共用，linkType=STUDENT|MENTOR|ADMIN。
     * 注册页打开时调用：valid=false 时 reason 给出具体失效原因，直接在页面显示失效提示。
     */
    @GetMapping("/register/validate")
    public ApiResponse<RegistrationLinkValidateResult> validateRegisterLink(
            @RequestParam String token, @RequestParam String linkType) {
        return ApiResponse.success(authService.validateRegistrationLink(token, linkType));
    }

    /**
     * 用户自助修改密码（已认证，password-management-and-id-validation D6/D7）。
     * 校验旧密码 + 新密码强度 + 新旧不同，BCrypt 重新哈希落库；
     * 恒审计（force=true，不受审计开关影响，防管理员借关审计掩盖）。
     */
    @Audited(action = "USER_CHANGE_PASSWORD", targetType = "USER",
            targetIdExpr = "T(com.nexcompute.management.security.SecurityUtils).getCurrentUserId()",
            force = true)
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(SecurityUtils.getCurrentUserId(),
                request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }

    /**
     * 忘记密码-发送验证码（公开，password-management-and-id-validation D3/D4）。
     * 无论账号是否存在/禁用/无邮箱均返回统一成功消息（防账号枚举）；
     * 限频由 service 抛 PASSWORD_RESET_SEND_TOO_FREQUENT，此处捕获后仍返回统一消息（不暴露限频原因）。
     */
    @PostMapping("/forgot-password/send-code")
    public ApiResponse<Map<String, String>> sendPasswordResetCode(
            @Valid @RequestBody ForgotPasswordSendCodeRequest request) {
        String message;
        try {
            message = authService.sendPasswordResetCode(request.getUsername());
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.PASSWORD_RESET_SEND_TOO_FREQUENT) {
                throw e;
            }
            // 限频：吞掉异常，返回统一消息（D4 防枚举，不暴露限频原因）
            message = AuthService.PASSWORD_RESET_SENT_MESSAGE;
        }
        return ApiResponse.success(Map.of("message", message));
    }

    /**
     * 忘记密码-重置（公开，password-management-and-id-validation D6/D7/D8）。
     * 校验验证码（未过期/未消费/未达尝试上限）+ 新密码强度，重置成功置验证码已消费。
     */
    @PostMapping("/forgot-password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ForgotPasswordResetRequest request) {
        authService.resetPassword(request.getUsername(), request.getCode(), request.getNewPassword());
        return ApiResponse.success();
    }

    /**
     * 邀请注册页工号/学号可用性校验（公开，password-management-and-id-validation D5）。
     * 先校验邀请链接有效（防开放枚举），有效时返回 existsByUsername 查重结果；
     * 邀请链接无效时返回不可用 + 邀请链接无效（不反馈存在性）。
     */
    @GetMapping("/register/check-username")
    public ApiResponse<UsernameAvailabilityResult> checkUsername(
            @RequestParam String token, @RequestParam String linkType, @RequestParam String studentId) {
        return ApiResponse.success(authService.checkUsernameAvailable(token, linkType, studentId));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoDto> me() {
        return ApiResponse.success(authService.getCurrentUserInfo(SecurityUtils.getCurrentUserId()));
    }

    @PutMapping("/me")
    public ApiResponse<UserInfoDto> updateProfile(@RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(authService.updateProfile(
                SecurityUtils.getCurrentUserId(), request.getRealName(), request.getEmail(), request.getPhone()));
    }
}
