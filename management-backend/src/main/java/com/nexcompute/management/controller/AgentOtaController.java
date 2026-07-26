package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.AgentUpgradeTask;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.AgentOtaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 受控端 OTA 升级接口（D7）。
 * 管理员上传新版 exe、按实例批量/单独下发 agent.upgrade、查询升级任务状态。
 */
@RestController
@RequestMapping("/admin/agent-upgrade")
@RequiredArgsConstructor
@RequirePermission(module = "agent-ota", action = RequirePermission.Action.EDIT)
public class AgentOtaController {

    private final AgentOtaService agentOtaService;

    /** 上传新版受控端 exe */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                    @RequestParam("version") String version) {
        return ApiResponse.success(agentOtaService.upload(file, version));
    }

    /** 已上传版本列表 */
    @GetMapping("/versions")
    public ApiResponse<List<Map<String, Object>>> versions() {
        return ApiResponse.success(agentOtaService.listVersions());
    }

    /** 删除已上传版本（D7 增删改查） */
    @DeleteMapping("/versions/{version}")
    public ApiResponse<Void> deleteVersion(@PathVariable String version) {
        agentOtaService.deleteVersion(version);
        return ApiResponse.success();
    }

    /** 批量/单独升级 */
    @PostMapping("/upgrade")
    public ApiResponse<List<AgentUpgradeTask>> upgrade(@RequestBody UpgradeRequest request) {
        return ApiResponse.success(agentOtaService.upgrade(request.getInstanceIds(), request.getVersion()));
    }

    /** 全部升级任务 */
    @GetMapping("/tasks")
    public ApiResponse<List<AgentUpgradeTask>> tasks() {
        return ApiResponse.success(agentOtaService.listTasks());
    }

    /** 某实例升级任务 */
    @GetMapping("/tasks/{instanceId}")
    public ApiResponse<List<AgentUpgradeTask>> tasksByInstance(@PathVariable Long instanceId) {
        return ApiResponse.success(agentOtaService.listTasksByInstance(instanceId));
    }

    @Data
    public static class UpgradeRequest {
        private List<Long> instanceIds;
        private String version;
    }
}
