package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.SystemInfo;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.SystemInfoRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 系统信息（platform-refinements #5）：管理员可设置，其他用户只读。
 */
@RestController
@RequestMapping("/system-info")
@RequiredArgsConstructor
public class SystemInfoController {

    private final SystemInfoRepository repository;

    @GetMapping
    public ApiResponse<SystemInfo> get() {
        return ApiResponse.success(repository.findSingleton()
                .orElseGet(() -> SystemInfo.builder().id((short) 1).build()));
    }

    @PutMapping
    public ApiResponse<SystemInfo> update(@RequestBody SystemInfoRequest request) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可设置系统信息");
        }
        SystemInfo info = repository.findSingleton()
                .orElseGet(() -> SystemInfo.builder().id((short) 1).build());
        info.setMaintainer(request.getMaintainer());
        info.setMaintainerPhone(request.getMaintainerPhone());
        info.setOwner(request.getOwner());
        info.setOwnerPhone(request.getOwnerPhone());
        return ApiResponse.success(repository.save(info));
    }

    @Data
    public static class SystemInfoRequest {
        private String maintainer;
        private String maintainerPhone;
        private String owner;
        private String ownerPhone;
    }
}
