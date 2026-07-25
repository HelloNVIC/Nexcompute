package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 公告服务（任务 13.2、13.3、13.4）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * 发布/编辑公告（任务 13.2）
     * 支持定向范围（全体/课题组/角色）与发布方式（立即/定时），入审计
     */
    @Audited(action = "ANNOUNCEMENT_PUBLISH", targetType = "ANNOUNCEMENT", targetIdExpr = "#result.id")
    @Transactional
    public Announcement publish(String title, String content, String targetScope,
                                Long targetId, String targetRole, String publishMode, Instant publishAt) {
        Long authorId = SecurityUtils.getCurrentUserId();
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Announcement ann = Announcement.builder()
                .title(title)
                .content(content)
                .targetScope(targetScope != null ? targetScope : "ALL")
                .targetId(targetId)
                .targetRole(targetRole)
                .publishMode(publishMode != null ? publishMode : "IMMEDIATE")
                .publishAt(publishAt != null ? publishAt : Instant.now())
                .status("IMMEDIATE".equals(publishMode) || publishMode == null ? "PUBLISHED" : "PENDING")
                .authorId(authorId)
                .authorName(author.getRealName())
                .build();
        ann = announcementRepository.save(ann);

        // 立即发布：触发通知
        if ("PUBLISHED".equals(ann.getStatus())) {
            notifyTargetUsers(ann);
        }

        log.info("[Announcement] 公告已创建: {} ({} mode={} scope={})",
                ann.getId(), title, ann.getPublishMode(), ann.getTargetScope());
        return ann;
    }

    @Audited(action = "ANNOUNCEMENT_UPDATE", targetType = "ANNOUNCEMENT", targetIdExpr = "#id")
    @Transactional
    public Announcement update(Long id, String title, String content) {
        Announcement ann = getAnnouncement(id);
        if (title != null) ann.setTitle(title);
        if (content != null) ann.setContent(content);
        return announcementRepository.save(ann);
    }

    @Audited(action = "ANNOUNCEMENT_DELETE", targetType = "ANNOUNCEMENT", targetIdExpr = "#id")
    @Transactional
    public void delete(Long id) {
        Announcement ann = getAnnouncement(id);
        announcementRepository.delete(ann);
    }

    /**
     * 公告查看（任务 13.4）
     * 按用户角色与课题组过滤定向给自己的公告
     */
    public List<Announcement> listVisible() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();
        User user = userRepository.findById(userId).orElseThrow();

        List<Announcement> published = announcementRepository.findByStatusOrderByPublishAtDesc("PUBLISHED");
        List<Announcement> result = new ArrayList<>();
        for (Announcement ann : published) {
            if (isVisibleTo(ann, role, user.getGroupId())) {
                result.add(ann);
            }
        }
        return result;
    }

    /** 管理员查看全部（含待发布） */
    public List<Announcement> listAll() {
        return announcementRepository.findAll();
    }

    public Announcement getAnnouncement(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
    }

    /**
     * 定时公告发布调度任务（任务 13.3）
     * 扫描待发布公告，到点自动发布
     */
    @Scheduled(fixedRate = 60000) // 每分钟扫描
    @Transactional
    public void publishScheduled() {
        List<Announcement> pending = announcementRepository
                .findByStatusAndPublishAtBefore("PENDING", Instant.now());
        for (Announcement ann : pending) {
            ann.setStatus("PUBLISHED");
            announcementRepository.save(ann);
            notifyTargetUsers(ann);
            log.info("[Announcement] 定时公告已发布: {}", ann.getId());
        }
    }

    private boolean isVisibleTo(Announcement ann, UserRole role, Long groupId) {
        return switch (ann.getTargetScope()) {
            case "ALL" -> true;
            case "GROUP" -> groupId != null && groupId.equals(ann.getTargetId());
            case "ROLE" -> ann.getTargetRole() != null
                    && role.name().equals(ann.getTargetRole());
            default -> false;
        };
    }

    private void notifyTargetUsers(Announcement ann) {
        String title = "新公告：" + ann.getTitle();
        switch (ann.getTargetScope()) {
            case "ALL" -> notificationService.notifyAll(NotificationType.ANNOUNCEMENT, ann.getId(), title, ann.getContent());
            case "GROUP" -> {
                if (ann.getTargetId() != null) {
                    notificationService.notifyGroupStudents(ann.getTargetId(),
                            NotificationType.ANNOUNCEMENT, ann.getId(), title, ann.getContent());
                }
            }
            case "ROLE" -> {
                if (ann.getTargetRole() != null) {
                    notificationService.notifyByRole(UserRole.valueOf(ann.getTargetRole()),
                            NotificationType.ANNOUNCEMENT, ann.getId(), title, ann.getContent());
                }
            }
        }
    }
}
