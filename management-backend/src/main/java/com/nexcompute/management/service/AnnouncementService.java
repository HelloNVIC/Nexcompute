package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.AnnouncementBannerDto;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 公告服务（任务 13.2、13.3、13.4；platform-env-ota-realtime D10 多选课题组）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementGroupRepository announcementGroupRepository;
    private final ResearchGroupRepository groupRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * 发布/编辑公告（任务 13.2；D10：GROUP 多选）
     * 支持定向范围（全体/课题组（可多选）/角色）与发布方式（立即/定时），入审计。
     * GROUP 时 targetId 单值废弃，改用 announcement_group 关联表。
     */
    @Audited(action = "ANNOUNCEMENT_PUBLISH", targetType = "ANNOUNCEMENT", targetIdExpr = "#result.id")
    @Transactional
    public Announcement publish(String title, String content, String targetScope,
                                Long targetId, String targetRole, String publishMode, Instant publishAt,
                                List<Long> targetGroupIds) {
        Long authorId = SecurityUtils.getCurrentUserId();
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String scope = targetScope != null ? targetScope : "ALL";
        Announcement ann = Announcement.builder()
                .title(title)
                .content(content)
                .targetScope(scope)
                .targetId("GROUP".equals(scope) ? null : targetId) // GROUP 用关联表，单值废弃
                .targetRole(targetRole)
                .publishMode(publishMode != null ? publishMode : "IMMEDIATE")
                .publishAt(publishAt != null ? publishAt : Instant.now())
                .status("IMMEDIATE".equals(publishMode) || publishMode == null ? "PUBLISHED" : "PENDING")
                .authorId(authorId)
                .authorName(author.getRealName())
                .build();
        ann = announcementRepository.save(ann);

        // GROUP：保存多对多课题组关联（覆盖旧关联）
        if ("GROUP".equals(scope)) {
            replaceTargetGroups(ann.getId(), targetGroupIds);
            ann.setTargetGroupIds(targetGroupIds != null ? targetGroupIds : List.of());
        }

        // 立即发布：触发通知
        if ("PUBLISHED".equals(ann.getStatus())) {
            notifyTargetUsers(ann);
        }

        log.info("[Announcement] 公告已创建: {} ({} mode={} scope={})",
                ann.getId(), title, ann.getPublishMode(), ann.getTargetScope());
        return ann;
    }

    /** 旧签名兼容（无多选） */
    @Transactional
    public Announcement publish(String title, String content, String targetScope,
                                Long targetId, String targetRole, String publishMode, Instant publishAt) {
        return publish(title, content, targetScope, targetId, targetRole, publishMode, publishAt, null);
    }

    @Audited(action = "ANNOUNCEMENT_UPDATE", targetType = "ANNOUNCEMENT", targetIdExpr = "#id")
    @Transactional
    public Announcement update(Long id, String title, String content, String targetScope,
                               String targetRole, String publishMode, Instant publishAt,
                               List<Long> targetGroupIds) {
        Announcement ann = getAnnouncement(id);
        if (title != null) ann.setTitle(title);
        if (content != null) ann.setContent(content);
        if (targetScope != null) {
            ann.setTargetScope(targetScope);
            if ("GROUP".equals(targetScope)) {
                ann.setTargetId(null);
                replaceTargetGroups(ann.getId(), targetGroupIds);
                ann.setTargetGroupIds(targetGroupIds != null ? targetGroupIds : List.of());
            } else {
                // 非 GROUP 清空关联
                announcementGroupRepository.deleteByAnnouncementId(ann.getId());
            }
        }
        if (targetRole != null) ann.setTargetRole(targetRole);
        if (publishMode != null) ann.setPublishMode(publishMode);
        if (publishAt != null) ann.setPublishAt(publishAt);
        return announcementRepository.save(ann);
    }

    /** 覆盖公告的多对多课题组关联 */
    private void replaceTargetGroups(Long announcementId, List<Long> groupIds) {
        announcementGroupRepository.deleteByAnnouncementId(announcementId);
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        for (Long gid : new LinkedHashSet<>(groupIds)) { // 去重
            announcementGroupRepository.save(AnnouncementGroup.builder()
                    .announcementId(announcementId)
                    .groupId(gid)
                    .build());
        }
    }

    @Audited(action = "ANNOUNCEMENT_DELETE", targetType = "ANNOUNCEMENT", targetIdExpr = "#id")
    @Transactional
    public void delete(Long id) {
        Announcement ann = getAnnouncement(id);
        announcementGroupRepository.deleteByAnnouncementId(id);
        announcementRepository.delete(ann);
    }

    /**
     * 公告查看（任务 13.4；D10：GROUP 按组集合匹配）
     */
    public List<Announcement> listVisible() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();
        User user = userRepository.findById(userId).orElseThrow();

        List<Announcement> published = announcementRepository.findByStatusOrderByPublishAtDesc("PUBLISHED");
        return enrichAndFilter(published, role, user);
    }

    /** 管理员查看全部（含待发布），附带多选课题组信息 */
    public List<Announcement> listAll() {
        List<Announcement> all = announcementRepository.findAll();
        enrichWithGroups(all);
        return all;
    }

    public Announcement getAnnouncement(Long id) {
        Announcement ann = announcementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
        enrichWithGroups(List.of(ann));
        return ann;
    }

    /**
     * 登录公告中央弹窗（D9）：定向当前用户且未读的公告。
     */
    public List<AnnouncementBannerDto> listLoginBanner() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();
        User user = userRepository.findById(userId).orElseThrow();

        List<Announcement> visible = announcementRepository.findByStatusOrderByPublishAtDesc("PUBLISHED").stream()
                .filter(a -> isVisibleTo(a, role, user))
                .toList();

        // 未读公告通知：refId(公告 id) -> notificationId
        Map<Long, Long> unreadMap = new HashMap<>();
        for (NotificationMessage m : notificationService.getUnreadByType(userId, NotificationType.ANNOUNCEMENT)) {
            if (m.getRefId() != null) {
                unreadMap.putIfAbsent(m.getRefId(), m.getId());
            }
        }

        List<AnnouncementBannerDto> result = new ArrayList<>();
        for (Announcement a : visible) {
            Long notificationId = unreadMap.get(a.getId());
            if (notificationId != null) {
                result.add(new AnnouncementBannerDto(a.getId(), notificationId,
                        a.getTitle(), a.getContent(), a.getAuthorName(), a.getPublishAt()));
            }
        }
        return result;
    }

    /**
     * 定时公告发布调度任务（任务 13.3）
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

    // ===== D10：多选课题组可见性判定 =====

    /** 批量填充 targetGroupIds/targetGroupNames */
    private void enrichWithGroups(List<Announcement> announcements) {
        if (announcements.isEmpty()) return;
        Set<Long> ids = announcements.stream().map(Announcement::getId).collect(Collectors.toSet());
        List<AnnouncementGroup> rels = announcementGroupRepository.findByAnnouncementIdIn(ids);
        Map<Long, List<Long>> byAnn = rels.stream().collect(Collectors.groupingBy(
                AnnouncementGroup::getAnnouncementId,
                Collectors.mapping(AnnouncementGroup::getGroupId, Collectors.toList())));
        Set<Long> groupIds = rels.stream().map(AnnouncementGroup::getGroupId).collect(Collectors.toSet());
        Map<Long, String> groupNameMap = groupIds.isEmpty() ? Map.of()
                : groupRepository.findAllById(groupIds).stream()
                        .collect(Collectors.toMap(ResearchGroup::getId, ResearchGroup::getName));
        for (Announcement a : announcements) {
            List<Long> gids = byAnn.getOrDefault(a.getId(), List.of());
            a.setTargetGroupIds(gids);
            a.setTargetGroupNames(gids.stream().map(gid -> groupNameMap.getOrDefault(gid, "已删除组#" + gid)).toList());
        }
    }

    /** 填充多选组信息并按可见性过滤 */
    private List<Announcement> enrichAndFilter(List<Announcement> announcements, UserRole role, User user) {
        enrichWithGroups(announcements);
        List<Announcement> result = new ArrayList<>();
        for (Announcement ann : announcements) {
            if (isVisibleTo(ann, role, user)) {
                result.add(ann);
            }
        }
        return result;
    }

    private boolean isVisibleTo(Announcement ann, UserRole role, User user) {
        return switch (ann.getTargetScope()) {
            case "ALL" -> true;
            case "GROUP" -> {
                List<Long> groupIds = ann.getTargetGroupIds();
                if (groupIds == null || groupIds.isEmpty()) {
                    yield false;
                }
                // 学生：自身组在目标组集合
                if (user.getGroupId() != null && groupIds.contains(user.getGroupId())) {
                    yield true;
                }
                // 导师：是任一目标组的 mentor
                yield groupRepository.findAllById(groupIds).stream()
                        .anyMatch(g -> user.getId().equals(g.getMentorId()));
            }
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
                // D10：通知所有目标课题组成员（学生 + 导师）
                List<Long> groupIds = ann.getTargetGroupIds();
                if (groupIds == null || groupIds.isEmpty()) {
                    // 兼容旧单值（已迁移，一般不会到这里）
                    if (ann.getTargetId() != null) {
                        notificationService.notifyGroupStudents(ann.getTargetId(),
                                NotificationType.ANNOUNCEMENT, ann.getId(), title, ann.getContent());
                    }
                    return;
                }
                Set<Long> notified = new HashSet<>();
                for (ResearchGroup g : groupRepository.findAllById(groupIds)) {
                    notificationService.notifyGroupStudents(g.getId(),
                            NotificationType.ANNOUNCEMENT, ann.getId(), title, ann.getContent());
                    if (g.getMentorId() != null && notified.add(g.getMentorId())) {
                        notificationService.notify(g.getMentorId(),
                                NotificationType.ANNOUNCEMENT, ann.getId(), title, ann.getContent());
                    }
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
