package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.AgentUpgradeTaskRepository;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.ImageSyncTaskRepository;
import com.nexcompute.management.repository.MachineAllocationRepository;
import com.nexcompute.management.repository.MonitoringHistoryRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.repository.PortAllocationRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.StoragePoolMigrationRepository;
import com.nexcompute.management.repository.StoragePoolRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PhysicalInstanceService.deleteInstance 单元测试（instance-identity：物理实例删除）。
 * 在线拒绝 / 占用聚合计数拒绝 / 级联清理与删除。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhysicalInstanceServiceDeleteTest {

    @Mock
    private PhysicalInstanceRepository instanceRepository;
    @Mock
    private MachineAllocationRepository allocationRepository;
    @Mock
    private ResearchGroupRepository groupRepository;
    @Mock
    private AgentCommandService agentCommandService;
    @Mock
    private ContainerRepository containerRepository;
    @Mock
    private StoragePoolRepository poolRepository;
    @Mock
    private PortAllocationRepository portAllocationRepository;
    @Mock
    private StoragePoolMigrationRepository migrationRepository;
    @Mock
    private ImageSyncTaskRepository imageSyncTaskRepository;
    @Mock
    private AgentCredentialRepository credentialRepository;
    @Mock
    private MonitoringHistoryRepository monitoringHistoryRepository;
    @Mock
    private AgentUpgradeTaskRepository agentUpgradeTaskRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PhysicalInstanceService service;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(7L);

        when(instanceRepository.findById(1L)).thenReturn(Optional.of(
                PhysicalInstance.builder().id(1L).instanceNumber("01").status("OFFLINE").build()));
        when(agentCommandService.isAgentConnected("01")).thenReturn(false);
        stubCounts(0, 0, 0, 0, 0, 0);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private void stubCounts(long alloc, long cont, long pool, long port, long mig, long sync) {
        when(allocationRepository.countByInstanceId(1L)).thenReturn(alloc);
        when(containerRepository.countByInstanceId(1L)).thenReturn(cont);
        when(poolRepository.countByInstanceId(1L)).thenReturn(pool);
        when(portAllocationRepository.countByInstanceId(1L)).thenReturn(port);
        when(migrationRepository.countBySourceInstanceIdOrTargetInstanceId(1L, 1L)).thenReturn(mig);
        when(imageSyncTaskRepository.countByInstanceId(1L)).thenReturn(sync);
    }

    @Test
    void delete_nonAdminRejected() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.MENTOR);

        assertThatThrownBy(() -> service.deleteInstance(1L, false))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.PERMISSION_DENIED);
        verify(instanceRepository, never()).delete(any(PhysicalInstance.class));
    }

    @Test
    void delete_onlineInstanceRejected() {
        when(agentCommandService.isAgentConnected("01")).thenReturn(true);

        assertThatThrownBy(() -> service.deleteInstance(1L, false))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.CONFLICT)
                .hasMessageContaining("实例在线");
        verify(instanceRepository, never()).delete(any(PhysicalInstance.class));
    }

    @Test
    void delete_occupiedInstanceRejectedWithCounts() {
        stubCounts(2, 3, 1, 0, 0, 0);

        assertThatThrownBy(() -> service.deleteInstance(1L, false))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.CONFLICT)
                .hasMessageContaining("已分配 2 个用户")
                .hasMessageContaining("3 个容器")
                .hasMessageContaining("1 个存储池")
                .hasMessageNotContaining("端口分配");
        verify(instanceRepository, never()).delete(any(PhysicalInstance.class));
    }

    @Test
    void delete_syncTasksRejectedWithoutForce_butForcedCascades() {
        // 非强制：镜像同步任务拦截并提示可强制
        stubCounts(0, 0, 0, 0, 0, 5);
        assertThatThrownBy(() -> service.deleteInstance(1L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5 条镜像同步任务")
                .hasMessageContaining("强制");
        verify(instanceRepository, never()).delete(any(PhysicalInstance.class));

        // 强制：跳过同步任务检查，任务行随实例级联删除
        service.deleteInstance(1L, true);
        verify(imageSyncTaskRepository).deleteByInstanceId(1L);
        verify(instanceRepository).delete(any(PhysicalInstance.class));
    }

    @Test
    void delete_successCascades() {
        service.deleteInstance(1L, false);

        // 级联清理：凭证 / 监控历史 / 公共镜像同步状态（休眠表，raw SQL）/ 升级任务记录 / 镜像同步任务
        verify(credentialRepository).deleteByInstanceId(1L);
        verify(monitoringHistoryRepository).deleteByInstanceId(1L);
        verify(jdbcTemplate).update(eq("DELETE FROM public_image_sync WHERE instance_id = ?"), eq(1L));
        verify(agentUpgradeTaskRepository).deleteByInstanceId(1L);
        verify(imageSyncTaskRepository).deleteByInstanceId(1L);
        verify(instanceRepository).delete(any(PhysicalInstance.class));
    }

    @Test
    void delete_notFound() {
        assertThatThrownBy(() -> service.deleteInstance(99L, false))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.INSTANCE_NOT_FOUND);
        verify(instanceRepository, never()).delete(any(PhysicalInstance.class));
        verify(jdbcTemplate, never()).update(any(String.class), anyLong());
    }
}
