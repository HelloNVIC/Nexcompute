package com.nexcompute.management.service;

import com.nexcompute.management.domain.NotificationMessage;
import com.nexcompute.management.domain.NotificationType;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.NotificationMessageRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知消息生成服务（任务 13.5、13.6、13.8）
 * 横切关注点，被容器/存储池/工单/公告模块调用生成通知，永久存储不自动删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SseService sseService;

    /**
     * 生成通知（任务 13.5）
     * 永久存储，用户离线时累积入库，上线后通过未读消息收件箱查看。
     */
    @Transactional
    public void notify(Long userId, NotificationType type, Long refId, String title, String content) {
        NotificationMessage msg = NotificationMessage.builder()
                .userId(userId)
                .type(type)
                .refId(refId)
                .title(title)
                .content(content)
                .isRead(false)
                .build();
        msg = messageRepository.save(msg);

        // 通过 SSE 实时推送（任务 13.8，用户离线时推送静默失败，消息已入库累积）
        sseService.pushNotification(userId, msg);
        // platform-env-ota-realtime D8：右下角实时 Toast（精简事件，容器/工单/存储池变动）
        pushToast(userId, type, title, content);
        log.debug("[Notification] 通知已生成: user={} type={} title={}", userId, type, title);
    }

    /** 推送右下角实时 Toast 事件（D8）：容器/工单/存储池变动映射为 *.changed 事件 */
    private void pushToast(Long userId, NotificationType type, String title, String content) {
        String eventType = toastEventType(type);
        if (eventType == null) {
            return; // ANNOUNCEMENT 不弹 Toast（登录公告中央弹窗处理）
        }
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("type", eventType);
        toast.put("title", title);
        toast.put("message", content);
        toast.put("link", toastLink(type));
        sseService.pushToUser(userId, eventType, toast);
    }

    private static String toastEventType(NotificationType type) {
        return switch (type) {
            case CONTAINER -> "container.changed";
            case TICKET -> "ticket.changed";
            case STORAGE_POOL -> "storage.changed";
            default -> null;
        };
    }

    private static String toastLink(NotificationType type) {
        return switch (type) {
            case CONTAINER -> "/containers";
            case TICKET -> "/tickets";
            case STORAGE_POOL -> "/storage-pools";
            default -> null;
        };
    }

    /** 批量通知（如公告定向发布） */
    @Transactional
    public void notifyBatch(List<Long> userIds, NotificationType type, Long refId, String title, String content) {
        for (Long userId : userIds) {
            notify(userId, type, refId, title, content);
        }
    }

    /**
     * 未读消息列表（任务 13.6）
     */
    public List<NotificationMessage> getUnread(Long userId) {
        return messageRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);
    }

    /** 某类型的未读通知（D9：登录公告未读判定） */
    public List<NotificationMessage> getUnreadByType(Long userId, NotificationType type) {
        return messageRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId).stream()
                .filter(m -> m.getType() == type)
                .toList();
    }

    /** 按 type + refId 查全部通知记录（公告已读/未读名单用） */
    public List<NotificationMessage> getByTypeAndRefId(NotificationType type, Long refId) {
        return messageRepository.findByTypeAndRefId(type, refId);
    }

    /**
     * 历史消息回溯查询（任务 13.6）
     */
    public Page<NotificationMessage> getAllMessages(Long userId, Pageable pageable) {
        return messageRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * 未读数量（任务 13.6）
     */
    public long getUnreadCount(Long userId) {
        return messageRepository.countByUserIdAndIsReadFalse(userId);
    }

    /**
     * 标记已读（任务 13.6）
     */
    @Transactional
    public void markAsRead(Long userId, Long messageId) {
        messageRepository.markAsRead(messageId, userId);
    }

    /**
     * 全部标记已读
     */
    @Transactional
    public void markAllAsRead(Long userId) {
        messageRepository.markAllAsRead(userId);
    }

    /** 通知课题组所有学生（导师操作场景） */
    @Transactional
    public void notifyGroupStudents(Long groupId, NotificationType type, Long refId, String title, String content) {
        userRepository.findByGroupId(groupId).forEach(u ->
                notify(u.getId(), type, refId, title, content));
    }

    /** 通知所有用户 */
    @Transactional
    public void notifyAll(NotificationType type, Long refId, String title, String content) {
        userRepository.findAll().forEach(u ->
                notify(u.getId(), type, refId, title, content));
    }

    /** 通知指定角色用户 */
    @Transactional
    public void notifyByRole(UserRole role, NotificationType type, Long refId, String title, String content) {
        userRepository.findByRole(role, Pageable.ofSize(1000)).forEach(u ->
                notify(u.getId(), type, refId, title, content));
    }
}
