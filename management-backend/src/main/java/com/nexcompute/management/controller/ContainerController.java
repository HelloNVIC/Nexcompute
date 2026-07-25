package com.nexcompute.management.controller;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.Container;
import com.nexcompute.management.domain.ImageMetadata;
import com.nexcompute.management.dto.ConnectionInfo;
import com.nexcompute.management.dto.CreateContainerRequest;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.ContainerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import java.util.List;
import java.util.Map;

/**
 * 容器生命周期接口（任务 10.3、10.5、10.6、10.8、10.9）
 */
@RestController
@RequestMapping("/containers")
@RequiredArgsConstructor
@RequirePermission(module = "container", action = RequirePermission.Action.VIEW)
public class ContainerController {

    private final ContainerService containerService;

    /** 列表（可见性过滤，任务 10.9） */
    @GetMapping
    public ApiResponse<List<Container>> list() {
        return ApiResponse.success(containerService.listVisible());
    }

    @GetMapping("/{id}")
    public ApiResponse<Container> get(@PathVariable Long id) {
        return ApiResponse.success(containerService.getContainer(id));
    }

    /** 创建容器（任务 10.3） */
    @PostMapping
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Container> create(@Valid @RequestBody CreateContainerRequest request) {
        return ApiResponse.success(containerService.createContainer(request));
    }

    /** 连接信息（任务 10.5） */
    @GetMapping("/{id}/connection")
    public ApiResponse<ConnectionInfo> connectionInfo(@PathVariable Long id) {
        return ApiResponse.success(containerService.getConnectionInfo(id));
    }

    /** 获取容器创建时的原始表单配置（任务 4：容器配置页回显） */
    @GetMapping("/{id}/form-snapshot")
    public ApiResponse<String> formSnapshot(@PathVariable Long id) {
        return ApiResponse.success(containerService.getFormSnapshot(id));
    }

    /** 生命周期：start / stop / restart / rm（任务 10.6） */
    @PostMapping("/{id}/{action}")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<AgentCommandResult> lifecycle(@PathVariable Long id, @PathVariable String action) {
        return ApiResponse.success(containerService.lifecycle(id, action));
    }

    /** SSH 密码重置（任务 10.8） */
    @PostMapping("/{id}/reset-ssh")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> resetSsh(@PathVariable Long id, @RequestBody ResetSshRequest request) {
        containerService.resetSshPassword(id, request.getPassword());
        return ApiResponse.success();
    }

    /**
     * 容器提交镜像持久化（platform-refinements 6.3）。
     * 弹窗输入镜像名/标签/项目/备注，受控端 docker commit + save 导出 tar 回传，回传完成置 READY。
     */
    @PostMapping("/{id}/commit-image")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<ImageMetadata> commitImage(@PathVariable Long id, @RequestBody CommitImageRequest request) {
        return ApiResponse.success(containerService.commitContainerImage(
                id, request.getImageName(), request.getImageTag(), request.getProject(), request.getNote()));
    }

    /** 共享容器（platform-refinements #2：按工号精准共享，可限时） */
    @PostMapping("/{id}/share")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> share(@PathVariable Long id, @RequestBody ShareContainerRequest request) {
        containerService.shareContainer(id, request.getTargetWorkerId(), request.getExpiresAt());
        return ApiResponse.success();
    }

    /** 取消共享（platform-refinements #2） */
    @DeleteMapping("/{id}/share/{shareId}")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> unshare(@PathVariable Long id, @PathVariable Long shareId) {
        containerService.unshareContainer(id, shareId);
        return ApiResponse.success();
    }

    /** 同机其他用户聚合统计（任务 10.9） */
    @GetMapping("/instances/{instanceId}/aggregation")
    public ApiResponse<Map<String, Object>> aggregation(@PathVariable Long instanceId) {
        return ApiResponse.success(containerService.getInstanceAggregation(instanceId));
    }

    /** 查看容器日志（platform-refinements #2）：近 N 条，前端勾选实时后轮询 */
    @GetMapping("/{id}/logs")
    public ApiResponse<String> logs(@PathVariable Long id, @RequestParam(defaultValue = "100") int tail) {
        return ApiResponse.success(containerService.getContainerLogs(id, tail));
    }

    /** 修改容器备注（platform-refinements #1） */
    @PutMapping("/{id}/remark")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> updateRemark(@PathVariable Long id, @RequestBody RemarkRequest request) {
        containerService.updateRemark(id, request.getRemark());
        return ApiResponse.success();
    }

    // ===== 在线容器终端（platform-refinements #2）=====

    @PostMapping("/{id}/terminal/open")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<String> terminalOpen(@PathVariable Long id,
                                             @RequestParam(required = false, defaultValue = "bash") String shell) {
        return ApiResponse.success(containerService.openTerminal(id, shell));
    }

    @PostMapping("/{id}/terminal/write")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> terminalWrite(@PathVariable Long id, @RequestBody TerminalIORequest request) {
        containerService.writeTerminal(id, request.getSessionId(), request.getData());
        return ApiResponse.success();
    }

    @GetMapping("/{id}/terminal/read")
    @RequirePermission(module = "container", action = RequirePermission.Action.VIEW)
    public ApiResponse<String> terminalRead(@PathVariable Long id, @RequestParam String sessionId) {
        return ApiResponse.success(containerService.readTerminal(id, sessionId));
    }

    @PostMapping("/{id}/terminal/close")
    @RequirePermission(module = "container", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> terminalClose(@PathVariable Long id, @RequestBody TerminalIORequest request) {
        containerService.closeTerminal(id, request.getSessionId());
        return ApiResponse.success();
    }

    @Data
    public static class ResetSshRequest {
        private String password;
    }

    @Data
    public static class CommitImageRequest {
        @NotBlank(message = "镜像名不能为空")
        private String imageName;
        private String imageTag;
        private String project;
        private String note;
    }

    @Data
    public static class ShareContainerRequest {
        @NotBlank(message = "工号不能为空")
        private String targetWorkerId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant expiresAt;
    }

    @Data
    public static class RemarkRequest {
        private String remark;
    }

    @Data
    public static class TerminalIORequest {
        private String sessionId;
        private String data; // base64
    }
}
