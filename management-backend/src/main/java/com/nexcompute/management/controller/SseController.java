package com.nexcompute.management.controller;

import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.sse.SseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 订阅端点（任务 11.4、13.8）
 * 监控与通知复用同一连接，通过事件类型区分通道。
 */
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseController {

    private final SseService sseService;

    @GetMapping("/subscribe")
    public SseEmitter subscribe(@RequestParam(required = false) String token) {
        // token 认证在 SSE 中通过 query 传递（EventSource 不支持自定义 header）
        // 实际应在此校验 token 并提取 userId，此处简化使用 SecurityContext
        Long userId = SecurityUtils.getCurrentUserId();
        return sseService.subscribe(userId);
    }
}
