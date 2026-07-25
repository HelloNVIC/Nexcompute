package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.ResourceQuota;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.ResourceQuotaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 资源配额管理接口（platform-improvements 任务 5.2）
 * 管理员 CRUD 用户/课题组配额；容器创建表单查询有效配额（默认值与上限）。
 */
@RestController
@RequestMapping("/quotas")
@RequiredArgsConstructor
@RequirePermission(module = "user", action = RequirePermission.Action.EDIT)
public class ResourceQuotaController {

    private final ResourceQuotaService quotaService;

    /** 查询指定用户/课题组配额 */
    @GetMapping
    public ApiResponse<ResourceQuota> get(@RequestParam String scope, @RequestParam Long ownerId) {
        Optional<ResourceQuota> quota = quotaService.getQuota(scope, ownerId);
        return ApiResponse.success(quota.orElse(null));
    }

    /** 新建/更新配额（upsert） */
    @PutMapping
    public ApiResponse<ResourceQuota> upsert(@RequestBody UpsertQuotaRequest request) {
        return ApiResponse.success(quotaService.upsertQuota(
                request.getScope(), request.getOwnerId(), request.toFields()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        quotaService.deleteQuota(id);
        return ApiResponse.success();
    }

    /**
     * 当前用户在目标物理实例上的有效配额（容器创建表单默认值与上限，任务 2.5）。
     * 非管理员亦可查询自己的有效配额，故单独放开。
     */
    @GetMapping("/effective")
    @RequirePermission(module = "container", action = RequirePermission.Action.VIEW)
    public ApiResponse<ResourceQuotaService.EffectiveQuota> effective(@RequestParam Long instanceId) {
        Long userId = com.nexcompute.management.security.SecurityUtils.getCurrentUserId();
        return ApiResponse.success(quotaService.getEffectiveQuota(userId, instanceId));
    }

    @Data
    public static class UpsertQuotaRequest {
        private String scope;        // USER / GROUP
        private Long ownerId;
        private Float maxCpuCores;
        private Long maxMemoryMb;
        private Long maxGpuMemoryMb;
        private Long maxShmMb;

        public ResourceQuotaService.QuotaFields toFields() {
            return new ResourceQuotaService.QuotaFields(
                    maxCpuCores, maxMemoryMb, maxGpuMemoryMb, maxShmMb);
        }
    }
}
