package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.Container;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.dto.HeartbeatRequest;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.sse.SseService;
import com.nexcompute.management.config.NexcomputeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HeartbeatService 单元测试（platform-improvements 任务 4.5）
 * 验证心跳回传的容器状态实时更新 container.status 并经 SSE 推送。
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatServiceTest {

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

    private HeartbeatRequest heartbeatWith(String dockerId, String dockerState) {
        HeartbeatRequest req = new HeartbeatRequest();
        req.setInstanceId(1L);
        req.setAgentToken("token-abc");
        req.setInstanceNumber("01");
        HeartbeatRequest.ContainerState cs = new HeartbeatRequest.ContainerState();
        cs.setId(dockerId);
        cs.setState(dockerState);
        req.setContainers(List.of(cs));
        return req;
    }

    private PhysicalInstance registeredInstance() {
        return PhysicalInstance.builder()
                .id(1L).instanceNumber("01").status("OFFLINE").build();
    }

    @Test
    void heartbeat_updatesContainerStatusAndPushesSse() {
        PhysicalInstance instance = registeredInstance();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(instance));
        AgentCredential cred = AgentCredential.builder().id(1L).instanceId(1L)
                .token("token-abc").revoked(false).build();
        when(credentialRepository.findByInstanceId(1L)).thenReturn(Optional.of(cred));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 已注册容器：dockerId 匹配，当前 RUNNING，心跳回传 exited -> STOPPED
        Container c = Container.builder()
                .id(7L).name("c1").ownerId(2L).instanceId(1L)
                .dockerId("abcdef123456").status("RUNNING").build();
        when(containerRepository.findByInstanceId(1L)).thenReturn(List.of(c));
        when(containerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        heartbeatService.processHeartbeat(heartbeatWith("abcdef123456", "exited"));

        // 状态更新为 STOPPED
        assertThat(c.getStatus()).isEqualTo("STOPPED");
        // 经 container SSE 事件推送容器所有者
        ArgumentCaptor<Object> dataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseService).pushToUser(eq(2L), eq("container"), dataCaptor.capture());
        assertThat(dataCaptor.getValue().toString()).contains("STOPPED");
    }

    @Test
    void heartbeat_unchangedStatus_doesNotPush() {
        PhysicalInstance instance = registeredInstance();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(instance));
        when(credentialRepository.findByInstanceId(1L)).thenReturn(Optional.of(
                AgentCredential.builder().instanceId(1L).token("token-abc").revoked(false).build()));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Container c = Container.builder()
                .id(7L).ownerId(2L).instanceId(1L).dockerId("abcdef").status("RUNNING").build();
        when(containerRepository.findByInstanceId(1L)).thenReturn(List.of(c));

        // 心跳回传 running -> 状态未变 -> 不推送
        heartbeatService.processHeartbeat(heartbeatWith("abcdef", "running"));

        assertThat(c.getStatus()).isEqualTo("RUNNING");
        verify(sseService, never()).pushToUser(anyLong(), anyString(), any());
    }
}
