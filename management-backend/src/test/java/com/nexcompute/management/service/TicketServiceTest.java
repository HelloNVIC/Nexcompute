package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TicketService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketHistoryRepository historyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ResearchGroupRepository groupRepository;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private TicketService ticketService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    void createTicket_student_success() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);

        User student = User.builder()
                .id(1L).username("student1").realName("张三")
                .role(UserRole.STUDENT).groupId(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(student));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(100L);
            return t;
        });

        Ticket result = ticketService.createTicket("需要 GPU", TicketType.RESOURCE, "申请 GPU 资源");

        assertThat(result.getSubmitterId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getType()).isEqualTo(TicketType.RESOURCE);
        verify(historyRepository).save(any(TicketHistory.class));
    }

    @Test
    void createTicket_withGroup_resolvesGroupName() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);

        User student = User.builder().id(1L).groupId(1L).realName("张三").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(student));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(
                ResearchGroup.builder().name("AI 课题组").build()));
        when(ticketRepository.save(any())).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });

        Ticket result = ticketService.createTicket("标题", TicketType.FAULT, "内容");

        assertThat(result.getGroupName()).isEqualTo("AI 课题组");
    }

    @Test
    void closeTicket_nonAdmin_throws() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.MENTOR);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(2L);

        Ticket ticket = Ticket.builder().id(1L).status("PENDING").submitterId(1L).build();
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.closeTicket(1L, "回复"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void closeTicket_admin_sendsNotification() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(3L);

        Ticket ticket = Ticket.builder()
                .id(1L).status("PENDING").submitterId(1L).title("工单标题").build();
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(3L)).thenReturn(Optional.of(
                User.builder().realName("管理员").build()));
        when(ticketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Ticket result = ticketService.closeTicket(1L, "已处理");

        assertThat(result.getStatus()).isEqualTo("CLOSED");
        assertThat(result.getReply()).isEqualTo("已处理");
        verify(notificationService).notify(eq(1L), eq(NotificationType.TICKET), eq(1L), anyString(), anyString());
        verify(historyRepository).save(any(TicketHistory.class));
    }

    @Test
    void closeTicket_notFound_throws() {
        when(ticketRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.closeTicket(999L, "回复"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listVisible_student_seesOwnOnly() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        Ticket own = Ticket.builder().id(1L).submitterId(1L).title("我的工单").build();
        when(ticketRepository.findBySubmitterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(own));

        List<Ticket> result = ticketService.listVisible();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubmitterId()).isEqualTo(1L);
    }

    @Test
    void listVisible_admin_seesAll() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        when(ticketRepository.findAll()).thenReturn(List.of(
                Ticket.builder().id(1L).submitterId(1L).build(),
                Ticket.builder().id(2L).submitterId(2L).build(),
                Ticket.builder().id(3L).submitterId(3L).build()
        ));

        List<Ticket> result = ticketService.listVisible();

        assertThat(result).hasSize(3);
    }

    @Test
    void listVisible_mentor_seesOwnAndGroupStudents() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.MENTOR);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(5L);

        User mentor = User.builder().id(5L).groupId(1L).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(mentor));
        when(ticketRepository.findBySubmitterIdOrderByCreatedAtDesc(5L))
                .thenReturn(List.of(Ticket.builder().id(1L).submitterId(5L).build()));
        when(ticketRepository.findByGroupIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(
                        Ticket.builder().id(2L).submitterId(6L).groupId(1L).build(),
                        Ticket.builder().id(3L).submitterId(7L).groupId(1L).build()
                ));

        List<Ticket> result = ticketService.listVisible();

        assertThat(result).hasSize(3); // 自己 1 + 课题组学生 2
    }
}
