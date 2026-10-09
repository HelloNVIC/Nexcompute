package com.nexcompute.management.repository;

import com.nexcompute.management.domain.NotificationMessage;
import com.nexcompute.management.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationMessageRepository extends JpaRepository<NotificationMessage, Long> {

    List<NotificationMessage> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);

    Page<NotificationMessage> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndIsReadFalse(Long userId);

    /** 公告已读/未读名单：按 type=ANNOUNCEMENT + refId(公告 id) 查全部通知记录 */
    List<NotificationMessage> findByTypeAndRefId(NotificationType type, Long refId);

    /** 删除该用户的全部消息（V38 用户删除级联：收件人没了消息无意义） */
    void deleteByUserId(Long userId);

    @Modifying
    @Query("UPDATE NotificationMessage n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.id = :id AND n.userId = :userId")
    int markAsRead(Long id, Long userId);

    @Modifying
    @Query("UPDATE NotificationMessage n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsRead(Long userId);
}
