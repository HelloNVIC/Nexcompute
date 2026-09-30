package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.registry.RegistryClient;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * ImageService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private ImageMetadataRepository imageRepository;
    @Mock
    private ImageShareRepository shareRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NexcomputeProperties properties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private EmailService emailService;
    @Mock
    private RegistryClient registryClient;

    @InjectMocks
    private ImageService imageService;

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
    void listVisible_student_seesOwnSharedAndPublic() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        ImageMetadata own = ImageMetadata.builder().id(1L).name("my-img").ownerId(1L).build();
        ImageMetadata pub = ImageMetadata.builder().id(2L).name("public-img").isPublic(true).build();
        ImageMetadata shared = ImageMetadata.builder().id(3L).name("shared-img").ownerId(2L).build();

        when(imageRepository.findByIsPublicTrue()).thenReturn(List.of(pub));
        when(imageRepository.findByVisibility(any())).thenReturn(List.of());
        when(imageRepository.findByOwnerId(1L)).thenReturn(List.of(own));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of(
                ImageShare.builder().imageId(3L).build()));
        when(imageRepository.findById(3L)).thenReturn(Optional.of(shared));

        List<ImageMetadata> result = imageService.listVisible();

        assertThat(result).hasSize(3); // 自有 + 公共 + 被共享
    }

    @Test
    void listVisible_admin_seesAll() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        ImageMetadata pub = ImageMetadata.builder().id(2L).isPublic(true).build();
        when(imageRepository.findByIsPublicTrue()).thenReturn(List.of(pub));
        when(imageRepository.findByVisibility(any())).thenReturn(List.of());
        when(imageRepository.findAll()).thenReturn(List.of(
                pub,
                ImageMetadata.builder().id(3L).ownerId(1L).build()
        ));

        List<ImageMetadata> result = imageService.listVisible();

        assertThat(result).hasSize(2); // 去重后（pub 同时在 public 和 findAll 中）
    }

    @Test
    void registerImage_savesMetadata() {
        User owner = User.builder().id(1L).realName("张三").groupId(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(properties.getStorage()).thenReturn(new NexcomputeProperties.Storage());

        ImageMetadata result = imageService.registerImage("pytorch", "latest", 1L, null, 1024L, "abc123");

        assertThat(result.getName()).isEqualTo("pytorch");
        assertThat(result.getTag()).isEqualTo("latest");
        assertThat(result.getOwnerName()).isEqualTo("张三");
        assertThat(result.getIsPublic()).isFalse();
        assertThat(result.getStatus()).isEqualTo("READY");
    }

    @Test
    void shareImage_newShare_saves() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        ImageMetadata img = ImageMetadata.builder().id(1L).ownerId(1L).build();
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(shareRepository.existsByImageIdAndSharedToUserId(1L, 2L)).thenReturn(false);

        imageService.shareImage(1L, 2L);

        verify(shareRepository).save(any(ImageShare.class));
    }

    @Test
    void shareImage_alreadyShared_doesNotSave() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        ImageMetadata img = ImageMetadata.builder().id(1L).ownerId(1L).build();
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(shareRepository.existsByImageIdAndSharedToUserId(1L, 2L)).thenReturn(true);

        imageService.shareImage(1L, 2L);

        verify(shareRepository, never()).save(any());
    }

    @Test
    void setVisibility_toSharedToAll_updatesVisibility() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        ImageMetadata img = ImageMetadata.builder().id(1L).ownerId(1L).visibility("PRIVATE").build();
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        imageService.setVisibility(1L, "SHARED_TO_ALL");

        assertThat(img.getVisibility()).isEqualTo("SHARED_TO_ALL");
    }

    @Test
    void getImage_notFound_throws() {
        when(imageRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imageService.getImage(999L))
                .isInstanceOf(BusinessException.class);
    }

    // platform-improvements 任务 2.3 / 3.6：imageRef 必须命中已配置镜像，拒绝自由文本
    @Test
    void resolveVisibleImage_rejectsFreeText() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        ImageMetadata own = ImageMetadata.builder().id(1L).name("pytorch").tag("latest").ownerId(1L)
                .status("READY").build();
        when(imageRepository.findByIsPublicTrue()).thenReturn(List.of());
        when(imageRepository.findByVisibility(any())).thenReturn(List.of());
        when(imageRepository.findByOwnerId(1L)).thenReturn(List.of(own));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of());

        // 命中已配置镜像 -> 返回
        ImageMetadata matched = imageService.resolveVisibleImage("pytorch:latest");
        assertThat(matched.getName()).isEqualTo("pytorch");

        // 自由文本未命中 -> 拒绝
        assertThatThrownBy(() -> imageService.resolveVisibleImage("ubuntu:22.04"))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.IMAGE_REF_NOT_ALLOWED);
    }

    // platform-improvements 任务 3.2 / 3.6：tar 解析失败时降级（不抛异常，返回空元数据）
    @Test
    void parseTarMetadata_invalidPath_returnsEmpty() {
        ImageService.TarMetadata meta = imageService.parseTarMetadata(
                java.nio.file.Paths.get("/nonexistent/path/that/does/not/exist.tar"));
        assertThat(meta.name()).isNull();
        assertThat(meta.appPorts()).isEmpty();
    }

    // ===== registry-image-distribution：登记 / 有效性 / resolveVisibleImage 新规则 / 无标记镜像 =====

    @Test
    void registerRegistryImage_savesUploadingRegistryImage() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        User owner = User.builder().id(1L).realName("张三").groupId(1L).role(UserRole.STUDENT).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(imageRepository.existsByNameAndTag("lab404-jupyter", "0.1")).thenReturn(false);
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ImageMetadata result = imageService.registerRegistryImage(
                "lab404-jupyter", "0.1", List.of(8888), "/workspace", "note");

        assertThat(result.getName()).isEqualTo("lab404-jupyter");
        assertThat(result.getTag()).isEqualTo("0.1");
        assertThat(result.getStatus()).isEqualTo("UPLOADING");
        assertThat(result.getDistribution()).isEqualTo(ImageService.DISTRIBUTION_REGISTRY);
        assertThat(result.getRegistryValid()).isNull();
        assertThat(result.getVisibility()).isEqualTo(ImageService.VIS_PRIVATE);
        assertThat(result.getTarPath()).isNull();
        assertThat(result.getAppPorts()).containsExactly(8888);
    }

    @Test
    void registerRegistryImage_duplicateNameTag_rejected() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(
                User.builder().id(1L).realName("张三").role(UserRole.STUDENT).build()));
        // 任何既有记录（含 TAR 类）name:tag 相同即拒绝
        when(imageRepository.existsByNameAndTag("pytorch", "latest")).thenReturn(true);

        assertThatThrownBy(() -> imageService.registerRegistryImage("pytorch", "latest", null, null, null))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.IMAGE_ALREADY_EXISTS);
        verify(imageRepository, never()).save(any());
    }

    @Test
    void refreshValidity_existsAndUploading_promotesReady() {
        ImageMetadata img = ImageMetadata.builder().id(1L).name("lab404-jupyter").tag("0.1")
                .status("UPLOADING").distribution(ImageService.DISTRIBUTION_REGISTRY).build();
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registryClient.exists("lab404-jupyter", "0.1")).thenReturn(true);

        ImageMetadata result = imageService.refreshValidity(1L);

        assertThat(result.getRegistryValid()).isTrue();
        assertThat(result.getRegistryCheckedAt()).isNotNull();
        assertThat(result.getStatus()).isEqualTo("READY");
    }

    @Test
    void refreshValidity_notExists_keepsStatus() {
        ImageMetadata img = ImageMetadata.builder().id(1L).name("lab404-jupyter").tag("0.1")
                .status("UPLOADING").distribution(ImageService.DISTRIBUTION_REGISTRY).build();
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registryClient.exists("lab404-jupyter", "0.1")).thenReturn(false);

        ImageMetadata result = imageService.refreshValidity(1L);

        assertThat(result.getRegistryValid()).isFalse();
        assertThat(result.getStatus()).isEqualTo("UPLOADING"); // 未提升
    }

    @Test
    void refreshValidity_registryUnreachable_throwsAndKeepsConclusion() {
        ImageMetadata img = ImageMetadata.builder().id(1L).name("lab404-jupyter").tag("0.1")
                .status("UPLOADING").registryValid(true).registryCheckedAt(Instant.now()).build();
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(registryClient.exists("lab404-jupyter", "0.1"))
                .thenThrow(new BusinessException(ErrorCode.REGISTRY_UNAVAILABLE, "仓库不可达"));

        assertThatThrownBy(() -> imageService.refreshValidity(1L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.REGISTRY_UNAVAILABLE);
        // 不可达不落库、不改变原结论
        verify(imageRepository, never()).save(any());
        assertThat(img.getRegistryValid()).isTrue();
    }

    @Test
    void resolveVisibleImage_invalidRegistryImage_notSelectable() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        // READY 但 registry_valid=false（无效仓库镜像）不可选；READY 且 true 可选
        ImageMetadata invalid = ImageMetadata.builder().id(1L).name("img-a").tag("latest").ownerId(1L)
                .status("READY").distribution(ImageService.DISTRIBUTION_REGISTRY).registryValid(false).build();
        ImageMetadata valid = ImageMetadata.builder().id(2L).name("img-b").tag("latest").ownerId(1L)
                .status("READY").distribution(ImageService.DISTRIBUTION_REGISTRY).registryValid(true).build();
        ImageMetadata unchecked = ImageMetadata.builder().id(3L).name("img-c").tag("latest").ownerId(1L)
                .status("READY").distribution(ImageService.DISTRIBUTION_REGISTRY).registryValid(null).build();
        ImageMetadata tar = ImageMetadata.builder().id(4L).name("img-d").tag("latest").ownerId(1L)
                .status("READY").distribution(ImageService.DISTRIBUTION_TAR).build();
        when(imageRepository.findByIsPublicTrue()).thenReturn(List.of());
        when(imageRepository.findByVisibility(any())).thenReturn(List.of());
        when(imageRepository.findByOwnerId(1L)).thenReturn(List.of(invalid, valid, unchecked, tar));
        when(shareRepository.findBySharedToUserId(1L)).thenReturn(List.of());

        assertThat(imageService.resolveVisibleImage("img-b:latest").getId()).isEqualTo(2L); // 有效可选
        assertThat(imageService.resolveVisibleImage("img-d:latest").getId()).isEqualTo(4L); // TAR 仅 READY
        assertThatThrownBy(() -> imageService.resolveVisibleImage("img-a:latest"))
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.IMAGE_REF_NOT_ALLOWED);
        assertThatThrownBy(() -> imageService.resolveVisibleImage("img-c:latest")) // 未检查亦不可选
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.IMAGE_REF_NOT_ALLOWED);
    }

    @Test
    void listUntagged_filtersKnownRefs() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        ImageMetadata known = ImageMetadata.builder().id(1L).name("lab404-jupyter").tag("0.1").build();
        when(imageRepository.findAll()).thenReturn(List.of(known));
        when(registryClient.catalog()).thenReturn(List.of("lab404-jupyter", "other-repo"));
        when(registryClient.tags("lab404-jupyter")).thenReturn(List.of("0.1", "0.2"));
        when(registryClient.tags("other-repo")).thenReturn(List.of("latest"));

        List<ImageService.UntaggedImage> result = imageService.listUntaggedRegistryImages();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).repo()).isEqualTo("lab404-jupyter");
        assertThat(result.get(0).tags()).containsExactly("0.2"); // 0.1 已登记被过滤
        assertThat(result.get(1).repo()).isEqualTo("other-repo");
        assertThat(result.get(1).tags()).containsExactly("latest");
    }

    @Test
    void listUntagged_nonAdmin_denied() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        assertThatThrownBy(() -> imageService.listUntaggedRegistryImages())
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(registryClient);
    }

    @Test
    void claimUntagged_createsReadyValidImage() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(9L);
        User admin = User.builder().id(9L).realName("管理员").role(UserRole.ADMIN).build();
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin));
        when(imageRepository.existsByNameAndTag("other-repo", "latest")).thenReturn(false);
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ImageMetadata result = imageService.claimUntaggedRegistryImage(
                "other-repo", "latest", List.of(6006), "/data", "usage", null);

        assertThat(result.getStatus()).isEqualTo("READY");
        assertThat(result.getDistribution()).isEqualTo(ImageService.DISTRIBUTION_REGISTRY);
        assertThat(result.getRegistryValid()).isTrue();
        assertThat(result.getSourceContainer()).isEqualTo("registry-claim");
        assertThat(result.getVisibility()).isEqualTo(ImageService.VIS_SHARED_TO_ALL); // 默认
        assertThat(result.getOwnerId()).isEqualTo(9L); // owner=当前管理员
    }

    @Test
    void claimUntagged_nonAdmin_denied() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.STUDENT);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

        assertThatThrownBy(() -> imageService.claimUntaggedRegistryImage(
                "other-repo", "latest", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.PERMISSION_DENIED);
        verify(imageRepository, never()).save(any());
    }
}
