package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.NotificationMessage;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 未读消息收件箱接口（任务 13.6）
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 未读消息列表 */
    @GetMapping("/unread")
    public ApiResponse<List<NotificationMessage>> unread() {
        return ApiResponse.success(notificationService.getUnread(SecurityUtils.getCurrentUserId()));
    }

    /** 历史消息回溯查询（含已读） */
    @GetMapping
    public ApiResponse<Page<NotificationMessage>> all(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(notificationService.getAllMessages(
                SecurityUtils.getCurrentUserId(), PageRequest.of(page, size)));
    }

    /** 未读数量 */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.success(Map.of("count",
                notificationService.getUnreadCount(SecurityUtils.getCurrentUserId())));
    }

    /** 标记已读 */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(SecurityUtils.getCurrentUserId(), id);
        return ApiResponse.success();
    }

    /** 全部已读 */
    @PostMapping("/read-all")
    public ApiResponse<Void> markAllAsRead() {
        notificationService.markAllAsRead(SecurityUtils.getCurrentUserId());
        return ApiResponse.success();
    }
}
