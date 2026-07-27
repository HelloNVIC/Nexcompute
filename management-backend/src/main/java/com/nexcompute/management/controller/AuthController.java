package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.dto.LoginRequest;
import com.nexcompute.management.dto.LoginResponse;
import com.nexcompute.management.dto.MentorRegisterRequest;
import com.nexcompute.management.dto.RegisterRequest;
import com.nexcompute.management.dto.UpdateProfileRequest;
import com.nexcompute.management.dto.UserInfoDto;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
