package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.Container;
import com.nexcompute.management.domain.ImageMetadata;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.registry.RegistryClient;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
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
 * ContainerService.commitContainerImage 单元测试（registry-image-distribution 任务 4.1）。
 * commit 编排改 tag+push 私有仓库：repo 命名沿用原 tar 规则、payload 带 registryUrl、
 * 成功置 READY+registry_valid=true、失败置 FAILED（状态落盘不被吞）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerServiceCommitImageTest {

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

    @InjectMocks
    private ContainerService containerService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(com.nexcompute.management.domain.UserRole.STUDENT);

        Container container = Container.builder()
                .id(1L).ownerId(1L).instanceId(1L).dockerId("docker-abc")
                .name("c-1").mountPoint("/workspace").build();
        when(containerRepository.findById(1L)).thenReturn(Optional.of(container));

        PhysicalInstance instance = PhysicalInstance.builder()
                .id(1L).instanceNumber("INST-1").status("ONLINE").build();
        when(instanceRepository.findById(1L)).thenReturn(Optional.of(instance));
        when(agentCommandService.isAgentConnected("INST-1")).thenReturn(true);

        User owner = User.builder().id(1L).realName("张三").studentId("2023123456").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        when(registryClient.getRegistryUrl()).thenReturn("10.13.66.25:5000");

        ImageMetadata registered = ImageMetadata.builder().id(5L).name("repo-x").tag("latest")
                .status("UPLOADING").distribution(ImageService.DISTRIBUTION_REGISTRY).build();
        when(imageService.registerCommitImage(any(), any(), eq(1L), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(ImageService.DISTRIBUTION_REGISTRY)))
                .thenReturn(registered);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    void commit_success_marksRegistryPushed() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.commit"), anyMap(), eq(1_800_000L)))
                .thenReturn(AgentCommandResult.builder()
                        .success(true).output("{\"repo\":\"repo-x\",\"tag\":\"latest\",\"sizeBytes\":\"10240\"}").build());
        when(imageService.markRegistryImagePushed(5L)).thenAnswer(inv -> {
            ImageMetadata img = ImageMetadata.builder().id(5L).name("repo-x").tag("latest")
                    .status("READY").registryValid(true).distribution(ImageService.DISTRIBUTION_REGISTRY).build();
            return img;
        });
        when(imageService.getImage(5L)).thenReturn(
                ImageMetadata.builder().id(5L).name("repo-x").tag("latest")
                        .status("READY").registryValid(true).build());

        ImageMetadata result = containerService.commitContainerImage(1L, "MyImg", "v1", "proj", "note");

        assertThat(result.getStatus()).isEqualTo("READY");
        assertThat(result.getRegistryValid()).isTrue();

        // repo 命名沿用原 tar 规则：工号-项目-镜像名-标签-备注-随机串（sanitize 为小写合法字符）
        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        verify(imageService).registerCommitImage(nameCap.capture(), eq("latest"), eq(1L), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(ImageService.DISTRIBUTION_REGISTRY));
        assertThat(nameCap.getValue()).matches("2023123456-proj-myimg-v1-note-[0-9a-f]{8}");

        // payload 带 registryUrl/repoName/tag
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        verify(agentCommandService).sendCommand(eq("INST-1"), eq("image.commit"), payloadCap.capture(), eq(1_800_000L));
        assertThat(payloadCap.getValue().get("registryUrl")).isEqualTo("10.13.66.25:5000");
        assertThat(payloadCap.getValue().get("repoName")).isEqualTo(nameCap.getValue());
        assertThat(payloadCap.getValue().get("tag")).isEqualTo("latest");

        verify(imageService).markRegistryImagePushed(5L);
        verify(imageService).updateSizeBytes(5L, 10240L);
    }

    @Test
    void commit_failure_marksFailedAndThrows() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.commit"), anyMap(), anyLong()))
                .thenReturn(AgentCommandResult.builder().success(false).error("push 失败").build());

        assertThatThrownBy(() -> containerService.commitContainerImage(1L, "myimg", "v1", "proj", null))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.CONTAINER_COMMAND_FAILED);

        // 失败状态落盘（markImageFailed 自带事务，防回滚吞状态）
        verify(imageService).markImageFailed(5L);
        verify(imageService, never()).markRegistryImagePushed(any());
    }

    @Test
    void buildCommitRepoName_sanitizesSegments() {
        // 大写折小写、中文折 '-'、空段 x 占位、非法字符折叠
        String repo = ContainerService.buildCommitRepoName("2023123456", "毕业设计", "Lab.Image_1", "v1.0", "");
        assertThat(repo).matches("2023123456-x-lab-image-1-v1-0-x-[0-9a-f]{8}");
        // 全空段也不产生空串/连续分隔符开头结尾
        String repo2 = ContainerService.buildCommitRepoName(null, null, null, null, null);
        assertThat(repo2).matches("x-x-x-x-x-[0-9a-f]{8}");
    }
}
