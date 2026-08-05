package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.ImageMetadata;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.ImageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 镜像管理接口（任务 9.4、9.5、9.7、9.8）
 */
@RestController
@RequestMapping("/images")
@RequiredArgsConstructor
@RequirePermission(module = "image", action = RequirePermission.Action.VIEW)
public class ImageController {

    private final ImageService imageService;

    /** 列出可见镜像（任务 9.4） */
    @GetMapping
    public ApiResponse<List<ImageMetadata>> list() {
        return ApiResponse.success(imageService.listVisible());
    }

    @GetMapping("/{id}")
    public ApiResponse<ImageMetadata> get(@PathVariable Long id) {
        return ApiResponse.success(imageService.getImage(id));
    }

    /** 设置镜像可见性（platform-refinements：权限弹窗"全员"选项） */
    @PostMapping("/{id}/visibility")
    @RequirePermission(module = "image", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> setVisibility(@PathVariable Long id, @RequestBody VisibilityRequest request) {
        imageService.setVisibility(id, request.getVisibility());
        return ApiResponse.success();
    }

    /** 编辑镜像应用端口、使用说明与容器内挂载点 */
    @PutMapping("/{id}/metadata")
    @RequirePermission(module = "image", action = RequirePermission.Action.EDIT)
    public ApiResponse<ImageMetadata> editMetadata(@PathVariable Long id, @RequestBody EditMetadataRequest request) {
        return ApiResponse.success(imageService.editMetadata(
                id, request.getAppPorts(), request.getUsageInstructions(), request.getMountPoint()));
    }
    @PostMapping("/{id}/share")
    @RequirePermission(module = "image", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> share(@PathVariable Long id, @RequestBody ShareRequest request) {
        if (request.getTargetUserId() != null) {
            imageService.shareImage(id, request.getTargetUserId());
        } else if (request.getTargetWorkerIds() != null && !request.getTargetWorkerIds().isEmpty()) {
            // platform-refinements #2：按工号共享给多个人
            for (String wid : request.getTargetWorkerIds()) {
                if (wid != null && !wid.isBlank()) {
                    imageService.shareImageByWorkerId(id, wid.trim());
                }
            }
        } else if (request.getTargetGroupId() != null) {
            imageService.shareImageToGroup(id, request.getTargetGroupId());
        } else {
            throw new com.nexcompute.management.common.BusinessException(
                    com.nexcompute.management.common.ErrorCode.BAD_REQUEST, "需指定目标用户/工号/课题组");
        }
        return ApiResponse.success();
    }

    /** 下载镜像 tar（platform-refinements #2） */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"image.tar\"");
        imageService.downloadTar(id, response.getOutputStream());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(module = "image", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        imageService.deleteImage(id);
        return ApiResponse.success();
    }

    /** 上传公共镜像（任务 9.7，管理员）-- 已撤回：公共镜像库移除，改用"全员可见"权限 */
    // platform-refinements：公共镜像库功能下线，保留 /agent/images/public/list 供受控端同步存量公共镜像

    /**
     * 解析 tar 元数据（不落盘，platform-refinements 任务 4.1）。
     * 前端选定 tar 后即时调用，解析 RepoTags/ExposedPorts 回填 name/tag/appPorts。
     */
    @PostMapping("/parse-tar")
    @RequirePermission(module = "image", action = RequirePermission.Action.VIEW)
    public ApiResponse<ParsedTar> parseTar(@RequestParam("file") org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {
        ImageService.TarMetadata parsed = imageService.parseTarMetadata(file.getInputStream());
        return ApiResponse.success(new ParsedTar(parsed.name(), parsed.tag(), parsed.appPorts()));
    }

    /** 公共镜像列表端点已下线（platform-refinements：公共镜像库移除）。受控端同步改用 /agent/images/public/list。 */

    /**
     * 上传 tar 文件作为镜像（任务 3，platform-improvements 任务 3.2/3.3）
     * 管理端直接存储 tar，解析 RepoTags/ExposedPorts 预填 name/tag 与 app_ports，
     * 返回解析后的镜像元数据（含 app_ports）。
     */
    @PostMapping("/upload-tar")
    @RequirePermission(module = "image", action = RequirePermission.Action.EDIT)
    public ApiResponse<ImageMetadata> uploadTar(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false, defaultValue = "latest") String tag,
            @RequestParam(required = false) String appPorts,
            @RequestParam(required = false) String usageInstructions,
            @RequestParam(required = false) String mountPoint) throws java.io.IOException {
        // 保存 tar 到管理端存储
        java.nio.file.Path dir = java.nio.file.Paths.get(imageService.getProperties().getStorage().getImageTarDir());
        java.nio.file.Files.createDirectories(dir);
        Long userId = com.nexcompute.management.security.SecurityUtils.getCurrentUserId();
        String tarPath = dir + "/" + userId + "/" + java.util.UUID.randomUUID() + ".tar";
        java.nio.file.Path target = java.nio.file.Paths.get(tarPath);
        java.nio.file.Files.createDirectories(target.getParent());
        file.transferTo(target.toFile());

        String checksum = sha256File(tarPath);
        List<Integer> appPortsList = parseAppPorts(appPorts);
        return ApiResponse.success(imageService.uploadTarImage(
                name, tag, userId, tarPath, file.getSize(), checksum, appPortsList, usageInstructions, mountPoint));
    }

    /** 解析前端传入的应用端口（逗号分隔，如 "8888,6006"），去重 + 校验 1-65535 */
    private List<Integer> parseAppPorts(String appPorts) {
        if (appPorts == null || appPorts.isBlank()) return null;
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (String p : appPorts.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                set.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
        List<Integer> result = set.isEmpty() ? null : new java.util.ArrayList<>(set);
        com.nexcompute.management.service.ImageService.validatePorts(result);
        return result;
    }

    private String sha256File(String path) throws java.io.IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new java.io.IOException("校验和计算失败", e);
        }
    }

    @Data
    public static class ShareRequest {
        /** 按 userId 共享 */
        private Long targetUserId;
        /** 按工号（studentId）精准共享（platform-refinements 6.6） */
        private String targetWorkerId;
        /** 按工号列表共享给多人（platform-refinements #2） */
        private java.util.List<String> targetWorkerIds;
        /** 共享给课题组全体（platform-refinements 6.6） */
        private Long targetGroupId;
    }

    /** 可见性设置（platform-refinements：全员/私有） */
    @Data
    public static class VisibilityRequest {
        private String visibility;
    }

    /** 编辑镜像元数据请求（应用端口 + 使用说明 + 容器内挂载点） */
    @Data
    public static class EditMetadataRequest {
        private List<Integer> appPorts;
        private String usageInstructions;
        /** 容器内挂载点（V31）：存储池映射到容器内的路径，创建容器时自动预填 */
        private String mountPoint;
    }

    /** parse-tar 返回（platform-refinements 4.1）：选定 tar 即时解析的 name/tag/appPorts */
    @Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ParsedTar {
        private String name;
        private String tag;
        private List<Integer> appPorts;
    }
}
