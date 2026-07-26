package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.Announcement;
import com.nexcompute.management.dto.AnnouncementBannerDto;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.AnnouncementService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 公告接口（任务 13.2、13.4）
 */
@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /** 所有用户查看定向给自己的公告（任务 13.4） */
    @GetMapping
    public ApiResponse<List<Announcement>> list() {
        return ApiResponse.success(announcementService.listVisible());
    }

    /** 登录公告中央弹窗（D9）：定向当前用户且未读的公告 */
    @GetMapping("/login-banner")
    public ApiResponse<List<AnnouncementBannerDto>> loginBanner() {
        return ApiResponse.success(announcementService.listLoginBanner());
    }

    @GetMapping("/{id}")
    public ApiResponse<Announcement> get(@PathVariable Long id) {
        return ApiResponse.success(announcementService.getAnnouncement(id));
    }

    /** 管理员发布公告（任务 13.2；D10：GROUP 多选 targetGroupIds） */
    @PostMapping
    @RequirePermission(module = "announcement", action = RequirePermission.Action.EDIT)
    public ApiResponse<Announcement> publish(@Valid @RequestBody PublishRequest request) {
        return ApiResponse.success(announcementService.publish(
                request.getTitle(), request.getContent(), request.getTargetScope(),
                request.getTargetId(), request.getTargetRole(), request.getPublishMode(), request.getPublishAt(),
                request.getTargetGroupIds()));
    }

    @PutMapping("/{id}")
    @RequirePermission(module = "announcement", action = RequirePermission.Action.EDIT)
    public ApiResponse<Announcement> update(@PathVariable Long id, @RequestBody PublishRequest request) {
        return ApiResponse.success(announcementService.update(id, request.getTitle(), request.getContent(),
                request.getTargetScope(), request.getTargetRole(), request.getPublishMode(), request.getPublishAt(),
                request.getTargetGroupIds()));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(module = "announcement", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        announcementService.delete(id);
        return ApiResponse.success();
    }

    /** 管理员查看全部（含待发布） */
    @GetMapping("/all")
    @RequirePermission(module = "announcement", action = RequirePermission.Action.VIEW)
    public ApiResponse<List<Announcement>> listAll() {
        return ApiResponse.success(announcementService.listAll());
    }

    @Data
    public static class PublishRequest {
        private String title;
        private String content;
        private String targetScope;  // ALL / GROUP / ROLE
        private Long targetId;
        private List<Long> targetGroupIds;  // D10：GROUP 多选课题组
        private String targetRole;
        private String publishMode;  // IMMEDIATE / SCHEDULED
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant publishAt;
    }
}
