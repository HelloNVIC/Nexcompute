package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.AgentLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 受控端日志查看接口（platform-audit-logging-ux D8）。
 * 选物理实例 → 选日期 → 查看受控端日志（尾行刷新）。
 */
@RestController
@RequestMapping("/admin/agent-logs")
@RequiredArgsConstructor
@RequirePermission(module = "agent-ota", action = RequirePermission.Action.VIEW)
public class AgentLogController {

    private final AgentLogService agentLogService;

    /** 列受控端日志文件列表 */
    @GetMapping("/instances/{instanceId}/files")
    public ApiResponse<List<Map<String, Object>>> files(@PathVariable Long instanceId) {
        return ApiResponse.success(agentLogService.listFiles(instanceId));
    }

    /** 拉取指定日期日志尾 N 行 */
    @GetMapping("/instances/{instanceId}/tail")
    public ApiResponse<Map<String, Object>> tail(
            @PathVariable Long instanceId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer tailLines) {
        return ApiResponse.success(agentLogService.tailLog(instanceId, date, tailLines));
    }
}
