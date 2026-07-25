package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.LocalAdminPasswordService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 全局受控端管理密码设置接口（platform-refinements 11.1）。
 * 所有受控端共用同一个明文密码；管理员在此统一设置后广播至所有已连接受控端。
 * 离线受控端上线后经心跳回包补推（HeartbeatController）。
 */
@RestController
@RequestMapping("/admin/local-admin-password")
@RequiredArgsConstructor
@RequirePermission(module = "physical-instance", action = RequirePermission.Action.EDIT)
public class LocalAdminPasswordController {

    private final LocalAdminPasswordService passwordService;

    /** 查询全局密码是否已设置（platform-refinements：修改前询问用，不回传明文） */
    @GetMapping
    public ApiResponse<PasswordStatus> status() {
        return ApiResponse.success(new PasswordStatus(passwordService.isSet()));
    }

    @PostMapping
    public ApiResponse<Void> setGlobalPassword(@RequestBody SetPasswordRequest request) {
        passwordService.setGlobalPassword(request.getPassword());
        return ApiResponse.success();
    }

    @Data
    public static class SetPasswordRequest {
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class PasswordStatus {
        private boolean set;
    }
}
