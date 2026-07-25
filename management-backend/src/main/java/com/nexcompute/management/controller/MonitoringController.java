package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.MonitoringHistory;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.MonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 监控接口（任务 11.5、11.8）
 */
@RestController
@RequestMapping("/monitoring")
@RequiredArgsConstructor
@RequirePermission(module = "monitoring", action = RequirePermission.Action.VIEW)
public class MonitoringController {

    private final MonitoringService monitoringService;

    /** 历史趋势（任务 11.8） */
    @GetMapping("/instances/{instanceId}/history")
    public ApiResponse<List<MonitoringHistory>> history(
            @PathVariable Long instanceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        return ApiResponse.success(monitoringService.getHistory(instanceId, start, end));
    }

    /** 最近 100 条历史（实时图表填充） */
    @GetMapping("/instances/{instanceId}/recent")
    public ApiResponse<List<MonitoringHistory>> recent(@PathVariable Long instanceId) {
        return ApiResponse.success(monitoringService.getRecentHistory(instanceId));
    }

    /** 进程列表（任务 11.5，按角色过滤） */
    @GetMapping("/instances/{instanceId}/processes")
    public ApiResponse<String> processes(@PathVariable Long instanceId) {
        return ApiResponse.success(monitoringService.getProcessList(instanceId));
    }

    /** 采集粒度（任务 11.2） */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.success(Map.of(
                "collectIntervalSeconds", monitoringService.getCollectIntervalSeconds()
        ));
    }
}
