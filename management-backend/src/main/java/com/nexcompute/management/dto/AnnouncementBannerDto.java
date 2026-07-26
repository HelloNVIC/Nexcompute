package com.nexcompute.management.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * 登录公告中央弹窗项（D9）。
 * notificationId 用于"已读"按钮标记该公告对应的通知已读。
 */
@Data
@AllArgsConstructor
public class AnnouncementBannerDto {
    private Long announcementId;
    private Long notificationId;
    private String title;
    private String content;
    private String authorName;
    private Instant publishedAt;
}
