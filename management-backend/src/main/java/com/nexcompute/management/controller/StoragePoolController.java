package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.StoragePool;
import com.nexcompute.management.domain.StoragePoolMigration;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.StoragePoolService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 存储池管理接口（任务 8.2、8.4、8.5）
 */
@RestController
@RequestMapping("/storage-pools")
@RequiredArgsConstructor
@RequirePermission(module = "storage-pool", action = RequirePermission.Action.VIEW)
public class StoragePoolController {

    private final StoragePoolService poolService;

    @GetMapping
    public ApiResponse<List<StoragePool>> list() {
        return ApiResponse.success(poolService.listAccessible());
    }

    @PostMapping
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.EDIT)
    public ApiResponse<StoragePool> create(@RequestBody CreatePoolRequest request) {
        return ApiResponse.success(poolService.createPool(request.getInstanceId(), request.getProjectName()));
    }

    @PostMapping("/{poolId}/share")
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> share(@PathVariable Long poolId, @RequestBody ShareRequest request) {
        poolService.sharePool(poolId, request.getTargetUserId());
        return ApiResponse.success();
    }

    @PostMapping("/{poolId}/revoke")
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.EDIT)
    public ApiResponse<StoragePoolService.RevokeShareResult> revoke(@PathVariable Long poolId, @RequestBody ShareRequest request) {
        return ApiResponse.success(poolService.revokeShare(poolId, request.getTargetUserId()));
    }

    @PostMapping("/{poolId}/migrate")
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.EDIT)
    public ApiResponse<StoragePoolMigration> migrate(@PathVariable Long poolId, @RequestBody MigrateRequest request) {
        return ApiResponse.success(poolService.migratePool(poolId, request.getTargetInstanceId()));
    }

    @PostMapping("/{poolId}/migrate/confirm")
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> confirmMigration(@PathVariable Long poolId) {
        poolService.confirmMigration(poolId);
        return ApiResponse.success();
    }

    /** 删除存储池（platform-refinements 7.1：前置检查无运行容器依赖） */
    @DeleteMapping("/{poolId}")
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long poolId) {
        poolService.deletePool(poolId);
        return ApiResponse.success();
    }

    /** 浏览存储池目录（platform-refinements #3） */
    @GetMapping("/{poolId}/files")
    public ApiResponse<List<Map<String, Object>>> listFiles(
            @PathVariable Long poolId,
            @RequestParam(required = false, defaultValue = "") String path) {
        return ApiResponse.success(poolService.listFiles(poolId, path));
    }

    /** 发起打包下载（platform-refinements #3：异步，返回 transferId 供轮询） */
    @PostMapping("/{poolId}/download/start")
    public ApiResponse<java.util.Map<String, String>> downloadStart(
            @PathVariable Long poolId,
            @RequestParam(required = false, defaultValue = "") String path) {
        return ApiResponse.success(java.util.Map.of("transferId", poolService.archiveStart(poolId, path)));
    }

    /** 查询打包下载进度（platform-refinements #3） */
    @GetMapping("/download/progress")
    public ApiResponse<java.util.Map<String, Object>> downloadProgress(@RequestParam String transferId) {
        return ApiResponse.success(poolService.downloadProgress(transferId));
    }

    /** 下载已就绪的打包文件（platform-refinements #3） */
    @GetMapping("/download/file")
    public void downloadFile(@RequestParam String transferId, HttpServletResponse response) throws IOException {
        poolService.downloadFile(transferId, response);
    }

    /** 上传文件至存储池（platform-refinements #3，支持文件夹：relativePath 保留结构） */
    @PostMapping("/{poolId}/upload")
    @RequirePermission(module = "storage-pool", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> upload(@PathVariable Long poolId,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false, defaultValue = "") String path,
                                    @RequestParam(required = false) String relativePath) throws IOException {
        poolService.uploadFile(poolId, path, relativePath, file);
        return ApiResponse.success();
    }

    @Data
    public static class CreatePoolRequest {
        private Long instanceId;
        private String projectName;
    }

    @Data
    public static class ShareRequest {
        private Long targetUserId;
    }

    @Data
    public static class MigrateRequest {
        private Long targetInstanceId;
    }
}
