package com.nexcompute.management.service;

import com.nexcompute.management.domain.NotificationMessage;
import com.nexcompute.management.domain.NotificationType;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.NotificationMessageRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.sse.SseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * NotificationService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationMessageRepository messageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SseService sseService;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void notify_createsMessageAndPushes() {
        NotificationMessage saved = NotificationMessage.builder()
                .id(1L).userId(10L).type(NotificationType.CONTAINER).build();
        when(messageRepository.save(any())).thenReturn(saved);

        notificationService.notify(10L, NotificationType.CONTAINER, 5L, "标题", "内容");

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messageRepository).save(captor.capture());
        NotificationMessage msg = captor.getValue();
        assertThat(msg.getUserId()).isEqualTo(10L);
        assertThat(msg.getType()).isEqualTo(NotificationType.CONTAINER);
        assertThat(msg.getRefId()).isEqualTo(5L);
        assertThat(msg.getIsRead()).isFalse();
        verify(sseService).pushNotification(eq(10L), any());
    }

    @Test
    void getUnread_delegatesToRepository() {
        NotificationMessage msg = NotificationMessage.builder()
                .userId(1L).type(NotificationType.TICKET).isRead(false).build();
        when(messageRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(msg));

        List<NotificationMessage> result = notificationService.getUnread(1L);

        assertThat(result).hasSize(1);
    }

    @Test
    void getUnreadCount_delegatesToRepository() {
        when(messageRepository.countByUserIdAndIsReadFalse(1L)).thenReturn(5L);

        long count = notificationService.getUnreadCount(1L);

        assertThat(count).isEqualTo(5L);
    }

    @Test
    void markAsRead_delegatesToRepository() {
        notificationService.markAsRead(1L, 100L);

        verify(messageRepository).markAsRead(100L, 1L);
    }

    @Test
    void markAllAsRead_delegatesToRepository() {
        notificationService.markAllAsRead(1L);

        verify(messageRepository).markAllAsRead(1L);
    }

    @Test
    void getAllMessages_delegatesToRepository() {
        when(messageRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(
                        NotificationMessage.builder().id(1L).build()
                )));

        var result = notificationService.getAllMessages(1L, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void notifyAll_notifiesAllUsers() {
        when(userRepository.findAll()).thenReturn(List.of(
                com.nexcompute.management.domain.User.builder().id(1L).build(),
                com.nexcompute.management.domain.User.builder().id(2L).build(),
                com.nexcompute.management.domain.User.builder().id(3L).build()
        ));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        notificationService.notifyAll(NotificationType.ANNOUNCEMENT, 1L, "公告", "内容");

        verify(messageRepository, times(3)).save(any());
        verify(sseService).pushNotification(eq(1L), any());
        verify(sseService).pushNotification(eq(2L), any());
        verify(sseService).pushNotification(eq(3L), any());
    }
}
