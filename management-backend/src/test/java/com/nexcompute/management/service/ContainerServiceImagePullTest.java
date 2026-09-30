package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.agent.ProgressRouter;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.Container;
import com.nexcompute.management.domain.ImageMetadata;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.PortAllocation;
import com.nexcompute.management.dto.CreateContainerRequest;
import com.nexcompute.management.registry.RegistryClient;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.sse.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContainerService.ensureImageLoaded 分支单元测试（registry-image-distribution 任务 5.3/5.4）。
 * REGISTRY 镜像 -> image.pull（payload 含 registryUrl/name:tag）+ SSE containerImagePull 进度；
 * TAR 镜像 -> 原 image.load 不变；旧版受控端未知命令错误透传并附升级提示。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerServiceImagePullTest {

    @Mock
    private ContainerRepository containerRepository;
    @Mock
    private PhysicalInstanceRepository instanceRepository;
    @Mock
    private StoragePoolRepository poolRepository;
    @Mock
    private StoragePoolShareRepository shareRepository;
    @Mock
    private PortAllocationService portAllocationService;
    @Mock
    private AgentCommandService agentCommandService;
    @Mock
    private UserRepository userRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private NotificationService notificationService;
    @Mock
    private ImageService imageService;
    @Mock
    private ResourceQuotaService quotaService;
    @Mock
    private MachineAllocationRepository machineAllocationRepository;
    @Mock
    private ContainerShareRepository containerShareRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private RegistryClient registryClient;
    @Mock
    private SseService sseService;

    @InjectMocks
    private ContainerService containerService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(com.nexcompute.management.domain.UserRole.STUDENT);

        PhysicalInstance instance = PhysicalInstance.builder()
                .id(1L).instanceNumber("INST-1").status("ONLINE").build();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(instance));

        when(quotaService.getEffectiveQuota(anyLong(), anyLong()))
                .thenReturn(ResourceQuotaService.EffectiveQuota.builder().build());
        when(machineAllocationRepository.findByInstanceIdAndUserId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        // docker 占用端口查询失败 -> 空集（保持原行为）
        when(agentCommandService.sendCommand(eq("INST-1"), eq("port.query_used"), anyMap(), anyLong()))
                .thenReturn(null);

        when(portAllocationService.allocatePort(eq(1L), eq(22), any()))
                .thenReturn(PortAllocation.builder().id(11L).instanceId(1L).containerPort(22).hostPort(10022).build());

        // container.create 成功
        when(agentCommandService.sendCommand(eq("INST-1"), eq("container.create"), anyMap(), anyLong()))
                .thenReturn(AgentCommandResult.builder().success(true)
                        .output("{\"containerId\":\"docker-xyz\"}").build());

        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        when(registryClient.getRegistryUrl()).thenReturn("10.13.66.25:5000");

        // createContainer 后段落盘需要 owner
        when(userRepository.findById(7L)).thenReturn(Optional.of(
                com.nexcompute.management.domain.User.builder().id(7L).realName("李四").studentId("2023999").build()));
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private CreateContainerRequest request() {
        CreateContainerRequest req = new CreateContainerRequest();
        req.setInstanceId(1L);
        req.setImageRef("lab404-jupyter:0.1");
        req.setSshPassword("pw");
        return req;
    }

    @Test
    void registryImage_dispatchesImagePullAndForwardsSseProgress() {
        ImageMetadata img = ImageMetadata.builder().id(2L).name("lab404-jupyter").tag("0.1")
                .status("READY").distribution(ImageService.DISTRIBUTION_REGISTRY).registryValid(true)
                .appPorts(java.util.List.of(8888)).build();
        when(imageService.resolveVisibleImage("lab404-jupyter:0.1")).thenReturn(img);
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(AgentCommandResult.builder().success(true).output("pulled").build());

        Container result = containerService.createContainer(request());

        assertThat(result.getDockerId()).isEqualTo("docker-xyz");
        // DB 记录仍为裸引用（展示/回显一致）
        assertThat(result.getImageRef()).isEqualTo("lab404-jupyter:0.1");

        // payload 含 registryUrl/name:tag 完整引用
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<ProgressRouter.ProgressListener> listenerCap =
                ArgumentCaptor.forClass(ProgressRouter.ProgressListener.class);
        verify(agentCommandService).sendCommand(eq("INST-1"), eq("image.pull"), payloadCap.capture(),
                eq(600000L), listenerCap.capture());
        assertThat(payloadCap.getValue().get("imageRef"))
                .isEqualTo("10.13.66.25:5000/lab404-jupyter:0.1");
        // 不再走 image.load
        verify(agentCommandService, never()).sendCommand(eq("INST-1"), eq("image.load"), anyMap(), anyLong());

        // container.create 下发完整仓库引用（受控端本地镜像仅存在该 tag 下，裸名会 No such image）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> createCap = ArgumentCaptor.forClass(Map.class);
        verify(agentCommandService).sendCommand(eq("INST-1"), eq("container.create"), createCap.capture(), eq(120000L));
        assertThat(createCap.getValue().get("imageRef"))
                .isEqualTo("10.13.66.25:5000/lab404-jupyter:0.1");

        // listener 收 progress 帧 -> SSE containerImagePush 到当前用户
        listenerCap.getValue().onProgress("cmd-1", "pulling", 45, "a1b2c3: Downloading 45%");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sseCap = ArgumentCaptor.forClass(Map.class);
        verify(sseService).pushToUser(eq(7L), eq("containerImagePull"), sseCap.capture());
        assertThat(sseCap.getValue())
                .containsEntry("imageRef", "10.13.66.25:5000/lab404-jupyter:0.1")
                .containsEntry("stage", "pulling")
                .containsEntry("percent", 45)
                .containsEntry("text", "a1b2c3: Downloading 45%");
    }

    @Test
    void tarImage_keepsOriginalImageLoad() {
        ImageMetadata img = ImageMetadata.builder().id(3L).name("old-img").tag("latest")
                .status("READY").distribution(ImageService.DISTRIBUTION_TAR)
                .tarPath("/data/storage/images/1/old.tar").build();
        when(imageService.resolveVisibleImage("old-img:latest")).thenReturn(img);
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.load"), anyMap(), eq(600000L)))
                .thenReturn(AgentCommandResult.builder().success(true).output("loaded").build());

        CreateContainerRequest req = request();
        req.setImageRef("old-img:latest");
        Container result = containerService.createContainer(req);

        assertThat(result.getDockerId()).isEqualTo("docker-xyz");
        verify(agentCommandService).sendCommand(eq("INST-1"), eq("image.load"), argThat(p ->
                "/data/storage/images/1/old.tar".equals(p.get("sourcePath"))), eq(600000L));
        verify(agentCommandService, never()).sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), anyLong(),
                any(ProgressRouter.ProgressListener.class));
        // TAR 镜像 container.create 维持裸 name:tag（docker load 导入即该 tag）
        verify(agentCommandService).sendCommand(eq("INST-1"), eq("container.create"), argThat(p ->
                "old-img:latest".equals(p.get("imageRef"))), eq(120000L));
    }

    @Test
    void oldAgentUnknownCommand_errorPassthroughWithUpgradeHint() {
        ImageMetadata img = ImageMetadata.builder().id(2L).name("lab404-jupyter").tag("0.1")
                .status("READY").distribution(ImageService.DISTRIBUTION_REGISTRY).registryValid(true).build();
        when(imageService.resolveVisibleImage("lab404-jupyter:0.1")).thenReturn(img);
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(AgentCommandResult.builder().success(false)
                        .error("未知命令类型: image.pull").build());

        assertThatThrownBy(() -> containerService.createContainer(request()))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.IMAGE_TRANSFER_FAILED)
                .hasMessageContaining("未知命令类型")
                .hasMessageContaining("升级受控端");
        // 分发失败不进入创建流程（不执行 container.create）
        verify(agentCommandService, never()).sendCommand(eq("INST-1"), eq("container.create"), anyMap(), anyLong());
    }
}
