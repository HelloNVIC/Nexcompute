package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.RegistrationLink;
import com.nexcompute.management.service.AdminRegistrationService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 管理员邀请注册链接管理（创建/查看/作废 ADMIN 类型链接）。
 * 仅管理员可操作（服务层强制 ADMIN 校验）。
 */
@RestController
@RequestMapping("/admin/admin-registration-links")
@RequiredArgsConstructor
public class AdminRegistrationController {

    private final AdminRegistrationService adminRegistrationService;

    /** 创建管理员邀请链接 */
    @PostMapping
    public ApiResponse<RegistrationLink> create(@RequestBody CreateAdminLinkRequest request) {
        return ApiResponse.success(adminRegistrationService.createAdminLink(
                request.getRemainingCount(), request.getExpireAt()));
    }

    /** 查看所有管理员邀请链接 */
    @GetMapping
    public ApiResponse<List<RegistrationLink>> list() {
        return ApiResponse.success(adminRegistrationService.listAdminLinks());
    }

    /** 作废管理员邀请链接 */
    @PostMapping("/{token}/revoke")
    public ApiResponse<Void> revoke(@PathVariable String token) {
        adminRegistrationService.revokeAdminLink(token);
        return ApiResponse.success();
    }

    @Data
    public static class CreateAdminLinkRequest {
        @Min(1)
        private int remainingCount;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant expireAt;
    }
}
