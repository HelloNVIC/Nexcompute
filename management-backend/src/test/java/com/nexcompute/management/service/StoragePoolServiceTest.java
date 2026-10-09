package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * StoragePoolService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class StoragePoolServiceTest {

    @Mock
    private StoragePoolRepository poolRepository;
    @Mock
    private StoragePoolShareRepository shareRepository;
    @Mock
    private StoragePoolMigrationRepository migrationRepository;
    @Mock
    private PhysicalInstanceRepository instanceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ContainerRepository containerRepository;
    @Mock
    private AgentCommandService agentCommandService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private StoragePoolService storagePoolService;

    private MockedStatic<SecurityUtils> securityUtilsMock;
    private StoragePool testPool;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        // platform-refinements #1：管理员走 findAll；学生走 自有+共享，测试用 STUDENT 覆盖后者
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);

        testPool = StoragePool.builder()
                .id(1L).poolName("01-2021001A-bert").projectName("bert")
                .ownerId(1L).instanceId(1L).instanceNumber("01")
                .userStudentId("2021001A").status("ACTIVE").build();
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    void createPool_offlineInstance_rejectedWithoutPersisting() {
        // 离线机器（受控端未连接）拒绝建池，且不落库半成品池（复核反馈：离线不可创建存储池）
        PhysicalInstance inst = PhysicalInstance.builder()
                .id(2L).instanceNumber("02").status("OFFLINE")
                .storageRoot("D:/lab404").build();
        when(instanceRepository.findById(2L)).thenReturn(Optional.of(inst));
        when(agentCommandService.isAgentConnected("02")).thenReturn(false);

        assertThatThrownBy(() -> storagePoolService.createPool(2L, "bert"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在线");
        verify(poolRepository, never()).save(any(StoragePool.class));
        verify(agentCommandService, never()).sendCommand(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void revokeShare_noRunningContainers_succeeds() {
        when(poolRepository.findById(1L)).thenReturn(Optional.of(testPool));
        when(containerRepository.findByStoragePoolIdAndStatus(1L, "RUNNING"))
                .thenReturn(List.of());
        StoragePoolShare share = StoragePoolShare.builder()
                .id(1L).poolId(1L).sharedToUserId(2L).build();
        when(shareRepository.findByPoolId(1L)).thenReturn(List.of(share));

        StoragePoolService.RevokeShareResult result =
                storagePoolService.revokeShare(1L, 2L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isBlocked()).isFalse();
        verify(shareRepository).delete(share);
    }

    @Test
    void revokeShare_withRunningContainers_blocks() {
        when(poolRepository.findById(1L)).thenReturn(Optional.of(testPool));
        Container running = Container.builder()
                .id(10L).name("c1").ownerId(2L).storagePoolId(1L).status("RUNNING").build();
        when(containerRepository.findByStoragePoolIdAndStatus(1L, "RUNNING"))
                .thenReturn(List.of(running));
        when(userRepository.findById(2L)).thenReturn(Optional.of(
                User.builder().realName("张三").email("z@test.com").phone("13800000000").build()));

        StoragePoolService.RevokeShareResult result =
                storagePoolService.revokeShare(1L, 2L);

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getBlockingContainers()).hasSize(1);
        assertThat(result.getContactInfo()).contains("张三");
        verify(shareRepository, never()).delete(any());
    }

    @Test
    void revokeShare_poolNotFound_throws() {
        when(poolRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storagePoolService.revokeShare(999L, 2L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sharePool_notExists_throws() {
        when(poolRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storagePoolService.sharePool(1L, 2L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sharePool_newShare_saves() {
        when(poolRepository.findById(1L)).thenReturn(Optional.of(testPool));
        when(shareRepository.existsByPoolIdAndSharedToUserId(1L, 2L)).thenReturn(false);

        storagePoolService.sharePool(1L, 2L);

        verify(shareRepository).save(any(StoragePoolShare.class));
        verify(notificationService).notify(eq(2L), eq(NotificationType.STORAGE_POOL), eq(1L), anyString(), anyString());
    }

    @Test
    void sharePool_alreadyShared_doesNotSave() {
        when(poolRepository.findById(1L)).thenReturn(Optional.of(testPool));
        when(shareRepository.existsByPoolIdAndSharedToUserId(1L, 2L)).thenReturn(true);

        storagePoolService.sharePool(1L, 2L);

        verify(shareRepository, never()).save(any());
    }

    @Test
    void migratePool_withRunningContainers_throws() {
        when(poolRepository.findById(1L)).thenReturn(Optional.of(testPool));
        when(agentCommandService.isAgentConnected(anyString())).thenReturn(true);
        // storage-guard：目标实例须已设存储池根目录，否则前置校验先抛（走不到运行容器检查）
        when(instanceRepository.findById(2L)).thenReturn(Optional.of(
                PhysicalInstance.builder().id(2L).instanceNumber("02").storageRoot("/data/pools").build()));
        Container running = Container.builder().status("RUNNING").build();
        when(containerRepository.findByStoragePoolIdAndStatus(1L, "RUNNING"))
                .thenReturn(List.of(running));

        assertThatThrownBy(() -> storagePoolService.migratePool(1L, 2L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listAccessible_returnsOwnAndShared() {
        when(poolRepository.findByOwnerId(1L)).thenReturn(List.of(testPool));
        StoragePoolShare share = StoragePoolShare.builder().poolId(2L).sharedToUserId(1L).build();
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of(share));
        StoragePool sharedPool = StoragePool.builder().id(2L).poolName("02-x-shared").build();
        when(poolRepository.findById(2L)).thenReturn(Optional.of(sharedPool));

        List<StoragePool> result = storagePoolService.listAccessible();

        assertThat(result).hasSize(2);
    }

    @Test
    void listAccessible_offlineWhenInstanceOffline() {
        testPool.setPoolPath("/data/storage/01-2021001A-bert");
        when(poolRepository.findByOwnerId(1L)).thenReturn(List.of(testPool));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of());
        PhysicalInstance offlineInstance = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("OFFLINE").build();
        when(instanceRepository.findAllById(any())).thenReturn(List.of(offlineInstance));

        List<StoragePool> result = storagePoolService.listAccessible();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOffline()).isTrue();
    }

    @Test
    void listAccessible_offlineWhenPathMissing() {
        // 物理机在线但 poolPath 缺失 -> 离线
        testPool.setPoolPath(null);
        when(poolRepository.findByOwnerId(1L)).thenReturn(List.of(testPool));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of());
        PhysicalInstance onlineInstance = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("ONLINE").build();
        when(instanceRepository.findAllById(any())).thenReturn(List.of(onlineInstance));

        List<StoragePool> result = storagePoolService.listAccessible();

        assertThat(result.get(0).getOffline()).isTrue();
    }

    @Test
    void listAccessible_onlineWhenInstanceOnlineAndPathPresent() {
        testPool.setPoolPath("/data/storage/01-2021001A-bert");
        when(poolRepository.findByOwnerId(1L)).thenReturn(List.of(testPool));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of());
        PhysicalInstance onlineInstance = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("ONLINE").build();
        when(instanceRepository.findAllById(any())).thenReturn(List.of(onlineInstance));

        List<StoragePool> result = storagePoolService.listAccessible();

        assertThat(result.get(0).getOffline()).isFalse();
    }

    @Test
    void listAccessible_migratingAndOfflineCoexist() {
        // 迁移中且物理机离线：离线为派生标识，不覆盖迁移状态
        testPool.setStatus("MIGRATING");
        testPool.setPoolPath("/data/storage/01-2021001A-bert");
        when(poolRepository.findByOwnerId(1L)).thenReturn(List.of(testPool));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of());
        PhysicalInstance offlineInstance = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("OFFLINE").build();
        when(instanceRepository.findAllById(any())).thenReturn(List.of(offlineInstance));

        List<StoragePool> result = storagePoolService.listAccessible();

        StoragePool pool = result.get(0);
        assertThat(pool.getStatus()).isEqualTo("MIGRATING"); // 迁移状态保留
        assertThat(pool.getOffline()).isTrue(); // 离线派生标识同时为真
    }

    @Test
    void confirmMigration_notCompleted_throws() {
        when(poolRepository.findById(1L)).thenReturn(Optional.of(testPool));
        when(agentCommandService.isAgentConnected(anyString())).thenReturn(true);
        StoragePoolMigration migration = StoragePoolMigration.builder()
                .id(1L).poolId(1L).status("TRANSFERRING").build();
        when(migrationRepository.findByPoolId(1L)).thenReturn(Optional.of(migration));

        assertThatThrownBy(() -> storagePoolService.confirmMigration(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPool_rejectsWhenStorageRootEmpty() {
        // 8.3 受控端未设置存储池根目录 -> 拒绝建池
        PhysicalInstance noRoot = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("ONLINE").storageRoot(null).build();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(noRoot));

        assertThatThrownBy(() -> storagePoolService.createPool(1L, "proj"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未设置存储池根目录");
    }

    @Test
    void createPool_rejectsWhenStorageRootBlank() {
        PhysicalInstance blankRoot = PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("ONLINE").storageRoot("  ").build();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(blankRoot));

        assertThatThrownBy(() -> storagePoolService.createPool(1L, "proj"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未设置存储池根目录");
    }
}
