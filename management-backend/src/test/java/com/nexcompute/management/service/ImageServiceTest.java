package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.*;
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
}
