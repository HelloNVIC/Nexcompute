package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.service.PermissionService;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 当前用户权限查询接口（复核反馈：前端需按权限矩阵渲染菜单与按钮）。
 * 独立于 /admin/permissions（那边类级 permission:EDIT 仅管理员），此处仅需登录——
 * 返回内容为当前登录用户自己角色的权限行，无敏感信息。
 */
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionQueryController {

    private final PermissionService permissionService;

    /** 当前登录用户角色的全模块权限（moduleCode -> view/edit/delete） */
    @GetMapping("/my")
    public ApiResponse<Map<String, PermissionService.RolePerm>> my() {
        return ApiResponse.success(permissionService.getPermissionsForRole(SecurityUtils.getCurrentRole()));
    }
}
