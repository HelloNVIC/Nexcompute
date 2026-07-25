package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.CreateUserRequest;
import com.nexcompute.management.dto.UpdateUserRequest;
import com.nexcompute.management.dto.UserInfoDto;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员用户管理接口（任务 2.6）
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@RequirePermission(module = "user", action = RequirePermission.Action.EDIT)
public class UserManagementController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<Page<UserInfoDto>> list(
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(userService.listUsers(role, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserInfoDto> get(@PathVariable Long id) {
        return ApiResponse.success(userService.getUser(id));
    }

    /** 用户所属课题组列表（platform-refinements 9.2：编辑弹窗回显） */
    @GetMapping("/{id}/groups")
    public ApiResponse<List<com.nexcompute.management.domain.ResearchGroup>> userGroups(@PathVariable Long id) {
        return ApiResponse.success(userService.getUserGroups(id));
    }

    @PostMapping
    public ApiResponse<UserInfoDto> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success(userService.createUser(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserInfoDto> update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return ApiResponse.success(userService.updateUser(id, request));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        userService.disableUser(id);
        return ApiResponse.success();
    }

    /** 删除用户（platform-refinements #3） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return ApiResponse.success();
    }

    /** 管理用户课题组归属（platform-refinements 9.1：加入/移除） */
    @PutMapping("/{id}/groups")
    public ApiResponse<UserInfoDto> updateGroups(@PathVariable Long id, @RequestBody UpdateGroupsRequest request) {
        return ApiResponse.success(userService.updateUserGroups(id, request.getGroupIds()));
    }

    /** 重置用户密码（platform-refinements 9.1） */
    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.getPassword());
        return ApiResponse.success();
    }

    @lombok.Data
    public static class UpdateGroupsRequest {
        private java.util.List<Long> groupIds;
    }

    @lombok.Data
    public static class ResetPasswordRequest {
        private String password;
    }
}
