package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口
 */
@RestController
public class HealthController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.success(Map.of(
                "status", "ok",
                "service", "nexcompute-management",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
