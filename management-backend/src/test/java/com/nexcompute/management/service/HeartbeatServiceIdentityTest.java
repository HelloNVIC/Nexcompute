package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.dto.HeartbeatRequest;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.sse.SseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * HeartbeatService 身份解析单元测试（instance-identity：指纹即身份）。
 * 去重链：SMBIOS UUID → 机器码（单独命中，不要求 MAC）→ 新建；
 * 复用路径经 processHeartbeat 主流程刷新 IP/主机名/版本/存储根目录等可变属性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeartbeatServiceIdentityTest {

    @Mock
    private PhysicalInstanceRepository instanceRepository;
    @Mock
    private AgentCredentialRepository credentialRepository;
    @Mock
    private NexcomputeProperties properties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private MonitoringService monitoringService;
    @Mock
    private ContainerRepository containerRepository;
    @Mock
    private SseService sseService;
    @Mock
    private com.nexcompute.management.agent.OtaProgressTracker otaProgressTracker;

    @InjectMocks
    private HeartbeatService heartbeatService;

    /** 未注册心跳（Go 零值：无 instanceId/instanceNumber） */
    private HeartbeatRequest firstHeartbeat() {
        HeartbeatRequest req = new HeartbeatRequest();
        req.setMachineName("DESKTOP-X");
        req.setIpAddress("10.13.70.99");
        req.setAgentVersion("0.2.0");
        req.setStorageRoot("D:\\lab404");
        return req;
    }

    private void stubSaveAndCredential() {
        when(instanceRepository.save(any(PhysicalInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(credentialRepository.findByInstanceId(anyLong()))
                .thenReturn(Optional.of(AgentCredential.builder().instanceId(1L).token("t").revoked(false).build()));
        when(credentialRepository.save(any(AgentCredential.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void autoRegister_smbiosHit_reusesInstanceWithoutNewNumber() {
        HeartbeatRequest req = firstHeartbeat();
        req.setSmbiosUUID("UUID-A");
        req.setMachineCode("GUID-1");
        PhysicalInstance existing = PhysicalInstance.builder()
                .id(5L).instanceNumber("05").machineCode("GUID-1").build();
        when(instanceRepository.findBySmbiosUuid("UUID-A")).thenReturn(Optional.of(existing));
        stubSaveAndCredential();

        PhysicalInstance result = heartbeatService.processHeartbeat(req);

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getInstanceNumber()).isEqualTo("05");
        // 复用：不生成新编号（不触发行数统计）、不建新凭证
        verify(instanceRepository, never()).count();
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void autoRegister_machineCodeAlone_reusesAndRefreshesAttributes() {
        // SMBIOS 缺失、MAC 为空（采集失败），仅机器码可匹配——旧逻辑会放弃匹配新建重复实例
        HeartbeatRequest req = firstHeartbeat();
        req.setMac("");
        req.setMachineCode("GUID-1");
        PhysicalInstance existing = PhysicalInstance.builder()
                .id(5L).instanceNumber("05").machineCode("GUID-1")
                .ipAddress("192.168.1.100").machineName("OLD-NAME")
                .agentVersion("0.1.0").storageRoot("").build();
        when(instanceRepository.findByMachineCode("GUID-1")).thenReturn(Optional.of(existing));
        stubSaveAndCredential();

        PhysicalInstance result = heartbeatService.processHeartbeat(req);

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getInstanceNumber()).isEqualTo("05");
        // 可变属性刷新为最新上报值（IP/主机名/版本/存储根目录）
        assertThat(result.getIpAddress()).isEqualTo("10.13.70.99");
        assertThat(result.getMachineName()).isEqualTo("DESKTOP-X");
        assertThat(result.getAgentVersion()).isEqualTo("0.2.0");
        assertThat(result.getStorageRoot()).isEqualTo("D:\\lab404");
        verify(instanceRepository, never()).count();
    }

    @Test
    void autoRegister_machineCodeDiffers_createsNewInstance() {
        HeartbeatRequest req = firstHeartbeat();
        req.setMac("AA:BB:CC:DD:EE:FF");
        req.setMachineCode("GUID-2");
        // 机器码无命中（MAC 是否撞库不参与身份判定）
        when(instanceRepository.findByMachineCode("GUID-2")).thenReturn(Optional.empty());
        when(instanceRepository.count()).thenReturn(3L);
        when(instanceRepository.existsByInstanceNumber("04")).thenReturn(false);
        stubSaveAndCredential();

        PhysicalInstance result = heartbeatService.processHeartbeat(req);

        assertThat(result.getInstanceNumber()).isEqualTo("04");
        assertThat(result.getMachineCode()).isEqualTo("GUID-2");
        assertThat(result.getMac()).isEqualTo("AA:BB:CC:DD:EE:FF");
        verify(credentialRepository).save(any(AgentCredential.class));
    }

    @Test
    void autoRegister_noFingerprints_createsNewInstance() {
        HeartbeatRequest req = firstHeartbeat();
        when(instanceRepository.count()).thenReturn(0L);
        when(instanceRepository.existsByInstanceNumber("01")).thenReturn(false);
        stubSaveAndCredential();

        PhysicalInstance result = heartbeatService.processHeartbeat(req);

        assertThat(result.getInstanceNumber()).isEqualTo("01");
        ArgumentCaptor<AgentCredential> credCap = ArgumentCaptor.forClass(AgentCredential.class);
        verify(credentialRepository).save(credCap.capture());
        assertThat(credCap.getValue().getInstanceId()).isEqualTo(result.getId());
    }
}
