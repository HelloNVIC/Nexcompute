package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.agent.ProgressRouter;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.ImageMetadata;
import com.nexcompute.management.domain.ImageSyncBatch;
import com.nexcompute.management.domain.ImageSyncTask;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.registry.RegistryClient;
import com.nexcompute.management.repository.ImageMetadataRepository;
import com.nexcompute.management.repository.ImageSyncBatchRepository;
import com.nexcompute.management.repository.ImageSyncTaskRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.sse.SseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ImageSyncService 单元测试（V36：镜像"同步到所有机器"）。
 * 在线实例经线程池异步 image.pull（以 timeout verify + 内存任务表轮询断言终态），
 * 离线实例即刻 OFFLINE_SKIPPED；进度帧 -> SSE imageSyncProgress + 节流落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageSyncServiceTest {

    @Mock
    private ImageMetadataRepository imageRepository;
    @Mock
    private PhysicalInstanceRepository instanceRepository;
    @Mock
    private ImageSyncBatchRepository batchRepository;
    @Mock
    private ImageSyncTaskRepository taskRepository;
    @Mock
    private AgentCommandService agentCommandService;
    @Mock
    private RegistryClient registryClient;
    @Mock
    private SseService sseService;

    @InjectMocks
    private ImageSyncService service;

    private MockedStatic<SecurityUtils> securityUtilsMock;
    /** 内存任务表：模拟 findById/save 的读写（worker 线程写、测试线程读，用并发 Map） */
    private final Map<Long, ImageSyncTask> taskDb = new ConcurrentHashMap<>();
    /** save 桩记录的调用时状态快照（status:percent） */
    private final List<String> savedStatuses =
            java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.ADMIN);

        ImageMetadata img = ImageMetadata.builder().id(2L).name("lab404-jupyter").tag("0.1")
                .status("READY").distribution(ImageService.DISTRIBUTION_REGISTRY).registryValid(true).build();
        when(imageRepository.findById(2L)).thenReturn(Optional.of(img));

        when(batchRepository.findFirstByImageIdAndStatusOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(ImageSyncBatch.class))).thenAnswer(inv -> {
            ImageSyncBatch b = inv.getArgument(0);
            b.setId(100L);
            return b;
        });
        when(batchRepository.findById(100L)).thenAnswer(inv ->
                Optional.of(ImageSyncBatch.builder().id(100L).imageId(2L).status("RUNNING").build()));

        when(instanceRepository.findAll()).thenReturn(List.of(
                PhysicalInstance.builder().id(1L).instanceNumber("INST-1").build(),
                PhysicalInstance.builder().id(2L).instanceNumber("INST-2").build()));
        when(agentCommandService.isAgentConnected("INST-1")).thenReturn(true);
        when(agentCommandService.isAgentConnected("INST-2")).thenReturn(false);

        when(registryClient.getRegistryUrl()).thenReturn("10.13.66.25:5000");

        when(taskRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<ImageSyncTask> list = inv.getArgument(0);
            long id = 200;
            for (ImageSyncTask t : list) {
                t.setId(++id);
                taskDb.put(t.getId(), t);
            }
            return list;
        });
        when(taskRepository.findByBatchIdOrderByIdAsc(anyLong()))
                .thenAnswer(inv -> new ArrayList<>(taskDb.values()));
        when(taskRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(taskDb.get(inv.getArgument(0, Long.class))));
        when(taskRepository.save(any(ImageSyncTask.class))).thenAnswer(inv -> {
            ImageSyncTask t = inv.getArgument(0);
            // 快照调用时状态（实体随后可能被终态写复用，不能在 verify 阶段读对象）
            savedStatuses.add(t.getStatus() + ":" + t.getPercent());
            taskDb.put(t.getId(), t);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
        taskDb.clear();
        savedStatuses.clear();
    }

    private String taskStatus(String instanceNumber) {
        return taskDb.values().stream()
                .filter(t -> instanceNumber.equals(t.getInstanceNumber()))
                .map(ImageSyncTask::getStatus).findFirst().orElse(null);
    }

    /** 轮询等待异步 worker 完成终态写入（最长 5 秒） */
    private static void awaitTrue(Supplier<Boolean> cond) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.get())) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void syncAll_onlinePullsWithFullRef_offlineSkipped() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(AgentCommandResult.builder().success(true).output("pulled").build());

        ImageSyncService.SyncBatchView view = service.syncAll(2L);

        assertThat(view.getTotal()).isEqualTo(2);
        // 离线任务创建即 OFFLINE_SKIPPED 终态
        assertThat(view.getTasks()).anyMatch(t -> "INST-2".equals(t.getInstanceNumber())
                && ImageSyncTask.STATUS_OFFLINE_SKIPPED.equals(t.getStatus()));
        // 在线任务异步下发完整仓库引用
        verify(agentCommandService, timeout(2000)).sendCommand(eq("INST-1"), eq("image.pull"), argThat(p ->
                        "10.13.66.25:5000/lab404-jupyter:0.1".equals(p.get("imageRef"))),
                eq(600000L), any(ProgressRouter.ProgressListener.class));
        // 在线任务终态 PULLED（percent=100），批次随后 DONE
        awaitTrue(() -> ImageSyncTask.STATUS_PULLED.equals(taskStatus("INST-1")));
        assertThat(taskDb.values().stream().filter(t -> "INST-1".equals(t.getInstanceNumber()))
                .findFirst().orElseThrow().getPercent()).isEqualTo(100);
        verify(batchRepository, timeout(2000)).save(argThat(b -> ImageSyncBatch.STATUS_DONE.equals(b.getStatus())));
        // 离线实例从不下发命令
        verify(agentCommandService, never()).sendCommand(eq("INST-2"), anyString(), anyMap(), anyLong(), any());
    }

    @Test
    void syncAll_alreadyExistsTerminal() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(AgentCommandResult.builder().success(true).output("already_exists").build());

        service.syncAll(2L);
        awaitTrue(() -> ImageSyncTask.STATUS_ALREADY_EXISTS.equals(taskStatus("INST-1")));
        verify(agentCommandService, timeout(2000)).sendCommand(eq("INST-1"), eq("image.pull"),
                anyMap(), eq(600000L), any(ProgressRouter.ProgressListener.class));
    }

    @Test
    void syncAll_timeoutMarksTaskTimeout() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(null);

        service.syncAll(2L);
        awaitTrue(() -> ImageSyncTask.STATUS_TIMEOUT.equals(taskStatus("INST-1")));
        ImageSyncTask t = taskDb.values().stream().filter(x -> "INST-1".equals(x.getInstanceNumber()))
                .findFirst().orElseThrow();
        assertThat(t.getErrorMessage()).contains("受控端无响应");
    }

    @Test
    void syncAll_failureMarksTaskFailedWithError() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(AgentCommandResult.builder().success(false).error("拉取失败: 网络 boom").build());

        service.syncAll(2L);
        awaitTrue(() -> ImageSyncTask.STATUS_FAILED.equals(taskStatus("INST-1")));
        ImageSyncTask t = taskDb.values().stream().filter(x -> "INST-1".equals(x.getInstanceNumber()))
                .findFirst().orElseThrow();
        assertThat(t.getErrorMessage()).contains("boom");
    }

    @Test
    void syncAll_progressListenerPushesSseAndPersistsPulling() {
        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenAnswer(inv -> {
                    // 先来一帧进度（此时任务未终态，应落库 PULLING），再返回结果
                    ProgressRouter.ProgressListener listener = inv.getArgument(4);
                    listener.onProgress("cmd-1", "pulling", 45, "a1b2c3: Downloading 45%");
                    return AgentCommandResult.builder().success(true).output("pulled").build();
                });

        service.syncAll(2L);
        awaitTrue(() -> ImageSyncTask.STATUS_PULLED.equals(taskStatus("INST-1")));

        // 进度帧 -> SSE imageSyncProgress（batchId/instanceNumber/status/percent/text）；
        // 终态帧也会推（PULLING 帧与 PULLED 帧各一）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sseCap = ArgumentCaptor.forClass(Map.class);
        verify(sseService, timeout(2000).atLeast(2)).pushToUser(eq(7L), eq("imageSyncProgress"), sseCap.capture());
        assertThat(sseCap.getAllValues()).anySatisfy(e -> assertThat(e)
                .containsEntry("batchId", 100L)
                .containsEntry("instanceNumber", "INST-1")
                .containsEntry("status", ImageSyncTask.STATUS_PULLING)
                .containsEntry("percent", 45)
                .containsEntry("text", "a1b2c3: Downloading 45%"));
        assertThat(sseCap.getAllValues()).anySatisfy(e -> assertThat(e)
                .containsEntry("status", ImageSyncTask.STATUS_PULLED));
        // 进度帧落库 PULLING（首次立即写，不受 1s 节流限制；经 save 桩状态快照断言）
        assertThat(savedStatuses).contains(ImageSyncTask.STATUS_PULLING + ":45");
    }

    @Test
    void syncAll_runningBatchRejected() {
        when(batchRepository.findFirstByImageIdAndStatusOrderByIdDesc(2L, ImageSyncBatch.STATUS_RUNNING))
                .thenReturn(Optional.of(ImageSyncBatch.builder().id(99L).imageId(2L)
                        .status(ImageSyncBatch.STATUS_RUNNING).build()));
        when(taskRepository.findByBatchIdOrderByIdAsc(99L)).thenReturn(List.of(
                ImageSyncTask.builder().id(1L).batchId(99L).instanceNumber("INST-1")
                        .status(ImageSyncTask.STATUS_PULLING).build()));

        assertThatThrownBy(() -> service.syncAll(2L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.CONFLICT)
                .hasMessageContaining("已有同步批次进行中");
        verify(agentCommandService, never()).sendCommand(anyString(), anyString(), anyMap(), anyLong(), any());
    }

    @Test
    void syncAll_nonAdminRejected() {
        securityUtilsMock.when(SecurityUtils::getCurrentRole).thenReturn(UserRole.MENTOR);

        assertThatThrownBy(() -> service.syncAll(2L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.PERMISSION_DENIED);
        verify(agentCommandService, never()).sendCommand(anyString(), anyString(), anyMap(), anyLong(), any());
    }

    @Test
    void syncAll_invalidImageRejected() {
        ImageMetadata tar = ImageMetadata.builder().id(3L).name("old").tag("latest")
                .status("READY").distribution("TAR").build();
        when(imageRepository.findById(3L)).thenReturn(Optional.of(tar));

        assertThatThrownBy(() -> service.syncAll(3L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.BAD_REQUEST)
                .hasMessageContaining("仅仓库类且有效的镜像");
        verify(agentCommandService, never()).sendCommand(anyString(), anyString(), anyMap(), anyLong(), any());
    }

    @Test
    void syncAll_activeBatchView() {
        assertThat(service.activeBatchOf(2L)).isNull();

        when(agentCommandService.sendCommand(eq("INST-1"), eq("image.pull"), anyMap(), eq(600000L),
                any(ProgressRouter.ProgressListener.class)))
                .thenReturn(AgentCommandResult.builder().success(true).output("pulled").build());
        service.syncAll(2L);

        when(batchRepository.findFirstByImageIdAndStatusOrderByIdDesc(2L, ImageSyncBatch.STATUS_RUNNING))
                .thenReturn(Optional.of(ImageSyncBatch.builder().id(100L).imageId(2L)
                        .status(ImageSyncBatch.STATUS_RUNNING).build()));
        ImageSyncService.SyncBatchView active = service.activeBatchOf(2L);
        assertThat(active).isNotNull();
        assertThat(active.getBatch().getId()).isEqualTo(100L);
        assertThat(active.getTasks()).hasSize(2);
    }
}
