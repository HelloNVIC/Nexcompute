package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.domain.Container;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.ContainerShareRepository;
import com.nexcompute.management.repository.GroupMemberRepository;
import com.nexcompute.management.repository.ImageShareRepository;
import com.nexcompute.management.repository.MachineAllocationRepository;
import com.nexcompute.management.repository.NotificationMessageRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.StoragePoolRepository;
import com.nexcompute.management.repository.StoragePoolShareRepository;
import com.nexcompute.management.repository.TicketRepository;
import com.nexcompute.management.repository.UserFieldConfigRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService.deleteUser 单元测试（V38 用户删除外键清理链）。
 * 占用拦截（容器含历史/存储池/工单）+ 随用户级联删除（消息/分配/共享关系）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceDeleteTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ResearchGroupRepository groupRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private UserFieldConfigRepository fieldConfigRepository;
    @Mock
    private ContainerRepository containerRepository;
    @Mock
    private ContainerShareRepository containerShareRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private NotificationMessageRepository notificationMessageRepository;
    @Mock
    private MachineAllocationRepository machineAllocationRepository;
    @Mock
    private StoragePoolRepository storagePoolRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private StoragePoolShareRepository storagePoolShareRepository;
    @Mock
    private ImageShareRepository imageShareRepository;

    @InjectMocks
    private UserService userService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(
                User.builder().id(2L).username("stu01").role(UserRole.STUDENT).build()));
        // 无占用
        when(containerRepository.findByOwnerId(2L)).thenReturn(List.of());
        when(storagePoolRepository.countByOwnerId(2L)).thenReturn(0L);
        when(ticketRepository.countBySubmitterId(2L)).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    void deleteUser_noBlockers_cascadesAndDeletes() {
        userService.deleteUser(2L);

        // 随用户级联：消息、名下分配、共享关系（容器共享含分享出/被分享、池共享、镜像共享）
        verify(notificationMessageRepository).deleteByUserId(2L);
        verify(machineAllocationRepository).findByUserId(2L);
        verify(containerShareRepository).findBySharedToUserId(2L);
        verify(containerShareRepository).findBySharedBy(2L);
        verify(storagePoolShareRepository).findBySharedToUserId(2L);
        verify(imageShareRepository).findBySharedToUserId(2L);
        verify(groupMemberRepository).findByUserId(2L);
        verify(userRepository).delete(any(User.class));
    }

    @Test
    void deleteUser_withStoppedContainers_rejected() {
        when(containerRepository.findByOwnerId(2L)).thenReturn(List.of(
                Container.builder().id(1L).ownerId(2L).status("STOPPED").build()));

        assertThatThrownBy(() -> userService.deleteUser(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1 个容器记录");
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteUser_withRunningContainers_rejected() {
        when(containerRepository.findByOwnerId(2L)).thenReturn(List.of(
                Container.builder().id(1L).ownerId(2L).status("RUNNING").build()));

        assertThatThrownBy(() -> userService.deleteUser(2L))
                .hasMessageContaining("运行中容器");
    }

    @Test
    void deleteUser_withPools_rejected() {
        when(storagePoolRepository.countByOwnerId(2L)).thenReturn(3L);

        assertThatThrownBy(() -> userService.deleteUser(2L))
                .hasMessageContaining("3 个存储池");
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteUser_withTickets_rejected() {
        when(ticketRepository.countBySubmitterId(2L)).thenReturn(2L);

        assertThatThrownBy(() -> userService.deleteUser(2L))
                .hasMessageContaining("2 个工单");
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteUser_selfOrNonAdmin_rejected() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(2L);
        assertThatThrownBy(() -> userService.deleteUser(2L))
                .hasMessageContaining("当前登录用户");

        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.MENTOR);
        assertThatThrownBy(() -> userService.deleteUser(2L))
                .hasMessageContaining("仅管理员");
        verify(userRepository, never()).delete(any(User.class));
    }
}
