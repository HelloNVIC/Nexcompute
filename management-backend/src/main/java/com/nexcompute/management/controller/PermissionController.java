package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.UserFieldConfig;
import com.nexcompute.management.dto.PermissionMatrixDto;
import com.nexcompute.management.dto.UpdatePermissionRequest;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.PermissionService;
import com.nexcompute.management.service.UserService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 权限矩阵配置接口（任务 2.3）
 * 仅管理员可操作。
 */
@RestController
@RequestMapping("/admin/permissions")
@RequiredArgsConstructor
@RequirePermission(module = "permission", action = RequirePermission.Action.EDIT)
public class PermissionController {

    private final PermissionService permissionService;
    private final UserService userService;

    @GetMapping
    public ApiResponse<List<PermissionMatrixDto>> getMatrix() {
        return ApiResponse.success(permissionService.getMatrix());
    }

    @PutMapping
    public ApiResponse<Void> updateMatrix(@Valid @RequestBody UpdatePermissionRequest request) {
        permissionService.updateMatrix(request.getRole(), request.getModuleCode(),
                request.isCanView(), request.isCanEdit(), request.isCanDelete());
        return ApiResponse.success();
    }

    /** 恢复默认权限矩阵（D13） */
    @PostMapping("/reset-default")
    public ApiResponse<Void> resetDefault() {
        permissionService.resetToDefault();
        return ApiResponse.success();
    }

    /** 用户信息必填项配置（platform-refinements #5） */
    @GetMapping("/user-field-config")
    public ApiResponse<UserFieldConfig> getFieldConfig() {
        return ApiResponse.success(userService.getFieldConfig());
    }

    @PutMapping("/user-field-config")
    public ApiResponse<UserFieldConfig> updateFieldConfig(@RequestBody FieldConfigRequest request) {
        return ApiResponse.success(userService.updateFieldConfig(
                request.getRealName(), request.getStudentId(), request.getEmail(),
                request.getPhone(), request.getGroupId()));
    }

    @Data
    public static class FieldConfigRequest {
        private Boolean realName;
        private Boolean studentId;
        private Boolean email;
        private Boolean phone;
        private Boolean groupId;
    }
}
