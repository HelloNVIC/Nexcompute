package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.registry.RegistryClient;
import com.nexcompute.management.repository.ImageMetadataRepository;
import com.nexcompute.management.repository.ImageSyncBatchRepository;
import com.nexcompute.management.repository.ImageSyncTaskRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.sse.SseService;
import com.nexcompute.management.security.SecurityUtils;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 镜像"同步到所有机器"服务（V36）。
 * 为全部在线实例下发 image.pull（每命令占一个线程，固定池 16），离线实例直接标离线跳过；
 * 批次与任务落库，进度经 ProgressRouter -> SSE（imageSyncProgress）实时推给发起管理员。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageSyncService {

    /** 每命令一个线程的固定池上限（超出排队；实验室规模机器数 < 16 不会触顶） */
    private static final int DISPATCH_POOL_SIZE = 16;
    /** image.pull 命令超时（与创建容器按需拉取一致） */
    private static final long PULL_TIMEOUT_MS = 600000;
    /** 进度落库节流间隔（毫秒）；SSE 每帧推（受控端已节流 200ms） */
    private static final long PERSIST_THROTTLE_MS = 1000;

    private final ImageMetadataRepository imageRepository;
    private final PhysicalInstanceRepository instanceRepository;
    private final ImageSyncBatchRepository batchRepository;
    private final ImageSyncTaskRepository taskRepository;
    private final AgentCommandService agentCommandService;
    private final RegistryClient registryClient;
    private final SseService sseService;

    private final ExecutorService dispatchPool = Executors.newFixedThreadPool(DISPATCH_POOL_SIZE);

    /** 批次视图：批次 + 任务列表 + 终态计数汇总 */
    @Data
    @Builder
    public static class SyncBatchView {
        private ImageSyncBatch batch;
        private List<ImageSyncTask> tasks;
        private long total;
        private long pulled;
        private long alreadyExists;
        private long failed;        // FAILED + TIMEOUT
        private long offlineSkipped;
    }

    /**
     * 发起"同步到所有机器"：校验（管理员 / REGISTRY / 有效 / 无进行中批次）→
     * 建批次与任务（离线即刻终态）→ 为每个在线任务提交线程执行 image.pull。
     */
    public SyncBatchView syncAll(Long imageId) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "同步到所有机器仅管理员可执行");
        }
        ImageMetadata image = imageRepository.findById(imageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
        if (!ImageService.DISTRIBUTION_REGISTRY.equals(image.getDistribution())
                || !Boolean.TRUE.equals(image.getRegistryValid())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "仅仓库类且有效的镜像可同步（请先确认镜像已推送到私有仓库）");
        }

        // 进行中批次拒绝；零任务批次视为创建期崩溃遗留自愈关闭（V36 部分唯一索引兜底并发双击）
        batchRepository.findFirstByImageIdAndStatusOrderByIdDesc(imageId, ImageSyncBatch.STATUS_RUNNING)
                .ifPresent(running -> {
                    if (!taskRepository.findByBatchIdOrderByIdAsc(running.getId()).isEmpty()) {
                        throw new BusinessException(ErrorCode.CONFLICT, "该镜像已有同步批次进行中");
                    }
                    running.setStatus(ImageSyncBatch.STATUS_DONE);
                    running.setFinishedAt(Instant.now());
                    batchRepository.save(running);
                });

        String imageRef = registryClient.getRegistryUrl() + "/" + image.getRef();
        Long adminUserId = SecurityUtils.getCurrentUserId();

        // 建批次与任务（离线任务即刻 OFFLINE_SKIPPED 终态）
        ImageSyncBatch batch;
        List<ImageSyncTask> tasks;
        List<ImageSyncTask> onlineTasks = new ArrayList<>();
        try {
            batch = batchRepository.save(ImageSyncBatch.builder()
                    .imageId(imageId)
                    .imageRef(imageRef)
                    .initiatedBy(adminUserId)
                    .status(ImageSyncBatch.STATUS_RUNNING)
                    .build());
            tasks = new ArrayList<>();
            for (PhysicalInstance inst : instanceRepository.findAll()) {
                boolean online = agentCommandService.isAgentConnected(inst.getInstanceNumber());
                ImageSyncTask task = ImageSyncTask.builder()
                        .batchId(batch.getId())
                        .instanceId(inst.getId())
                        .instanceNumber(inst.getInstanceNumber())
                        .status(online ? ImageSyncTask.STATUS_PENDING : ImageSyncTask.STATUS_OFFLINE_SKIPPED)
                        .finishedAt(online ? null : Instant.now())
                        .build();
                tasks.add(task);
                if (online) {
                    onlineTasks.add(task);
                }
            }
            tasks = taskRepository.saveAll(tasks);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 并发双击撞 uq_image_sync_batch_running 唯一索引等
            log.warn("[image-sync] 创建批次失败（可能并发重复发起）: {}", e.getMessage());
            throw new BusinessException(ErrorCode.CONFLICT, "该镜像已有同步批次进行中");
        }

        // 剩余终态计数：仅在线任务参与（离线任务创建即终态）
        AtomicInteger remaining = new AtomicInteger(onlineTasks.size());
        if (onlineTasks.isEmpty()) {
            finishBatch(batch.getId());
            return viewOf(batch.getId());
        }
        for (ImageSyncTask task : onlineTasks) {
            dispatchPullTask(batch.getId(), task, imageRef, adminUserId, remaining);
        }
        return viewOf(batch.getId());
    }

    /** 查询批次视图（刷新恢复用）；不存在抛 IMAGE_NOT_FOUND */
    public SyncBatchView viewOf(Long batchId) {
        ImageSyncBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND, "同步批次不存在"));
        return buildView(batch);
    }

    /** 查某镜像进行中的批次视图；无则返回 null */
    public SyncBatchView activeBatchOf(Long imageId) {
        return batchRepository
                .findFirstByImageIdAndStatusOrderByIdDesc(imageId, ImageSyncBatch.STATUS_RUNNING)
                .map(this::buildView)
                .orElse(null);
    }

    private SyncBatchView buildView(ImageSyncBatch batch) {
        List<ImageSyncTask> tasks = taskRepository.findByBatchIdOrderByIdAsc(batch.getId());
        long pulled = tasks.stream().filter(t -> ImageSyncTask.STATUS_PULLED.equals(t.getStatus())).count();
        long already = tasks.stream().filter(t -> ImageSyncTask.STATUS_ALREADY_EXISTS.equals(t.getStatus())).count();
        long failed = tasks.stream().filter(t -> ImageSyncTask.STATUS_FAILED.equals(t.getStatus())
                || ImageSyncTask.STATUS_TIMEOUT.equals(t.getStatus())).count();
        long skipped = tasks.stream().filter(t -> ImageSyncTask.STATUS_OFFLINE_SKIPPED.equals(t.getStatus())).count();
        return SyncBatchView.builder()
                .batch(batch)
                .tasks(tasks)
                .total(tasks.size())
                .pulled(pulled)
                .alreadyExists(already)
                .failed(failed)
                .offlineSkipped(skipped)
                .build();
    }

    /**
     * 单个在线任务的拉取执行（dispatchPool 线程内）：
     * 五参 sendCommand（listener 生命周期=命令生命周期）→ 进度帧节流落库 + 每帧 SSE →
     * 结果映射终态（pulled/already_exists/失败/超时）。
     */
    private void dispatchPullTask(Long batchId, ImageSyncTask task, String imageRef,
                                  Long adminUserId, AtomicInteger remaining) {
        dispatchPool.submit(() -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("imageRef", imageRef);
            AtomicLong lastPersist = new AtomicLong(0);
            AtomicBoolean persistedPulling = new AtomicBoolean(false);
            try {
                AgentCommandResult result = agentCommandService.sendCommand(
                        task.getInstanceNumber(), "image.pull", payload, PULL_TIMEOUT_MS,
                        (commandId, stage, percent, text) -> {
                            pushEvent(adminUserId, batchId, task.getInstanceNumber(),
                                    ImageSyncTask.STATUS_PULLING, percent, text, null);
                            // 落库节流：首次进入 PULLING 立即写，其后 ≥1s
                            long now = System.currentTimeMillis();
                            if (persistedPulling.compareAndSet(false, true)
                                    || now - lastPersist.get() >= PERSIST_THROTTLE_MS) {
                                lastPersist.set(now);
                                taskRepository.findById(task.getId()).ifPresent(t -> {
                                    if (t.isTerminal()) {
                                        return; // 迟到进度帧不覆盖终态
                                    }
                                    t.setStatus(ImageSyncTask.STATUS_PULLING);
                                    t.setPercent(percent == null ? 0 : percent);
                                    if (text != null) {
                                        t.setLastText(truncate(text, 500));
                                    }
                                    taskRepository.save(t);
                                });
                            }
                        });
                finalizeTask(batchId, task.getId(), terminalStatusFrom(result),
                        result == null ? "受控端无响应（超时）" : result.getError(), adminUserId, remaining);
            } catch (Exception e) {
                log.warn("[image-sync] 任务异常 instance={}: {}", task.getInstanceNumber(), e.getMessage());
                finalizeTask(batchId, task.getId(), ImageSyncTask.STATUS_FAILED, e.getMessage(), adminUserId, remaining);
            }
        });
    }

    /** 命令结果 → 任务终态 */
    private String terminalStatusFrom(AgentCommandResult result) {
        if (result == null) {
            return ImageSyncTask.STATUS_TIMEOUT;
        }
        if (!result.isSuccess()) {
            return ImageSyncTask.STATUS_FAILED;
        }
        if ("already_exists".equals(result.getOutput())) {
            return ImageSyncTask.STATUS_ALREADY_EXISTS;
        }
        return ImageSyncTask.STATUS_PULLED;
    }

    /** 任务终态落库 + SSE 终态帧 + 全部终态后关批次 */
    private void finalizeTask(Long batchId, Long taskId, String status, String error,
                              Long adminUserId, AtomicInteger remaining) {
        Integer percent = ImageSyncTask.STATUS_PULLED.equals(status) ? 100 : null;
        Optional<ImageSyncTask> opt = taskRepository.findById(taskId);
        if (opt.isPresent()) {
            ImageSyncTask t = opt.get();
            t.setStatus(status);
            t.setPercent(percent);
            t.setErrorMessage(error == null ? null : truncate(error, 1000));
            t.setFinishedAt(Instant.now());
            taskRepository.save(t);
            pushEvent(adminUserId, batchId, t.getInstanceNumber(), status, percent, null, error);
        }
        if (remaining.decrementAndGet() <= 0) {
            finishBatch(batchId);
        }
    }

    private void finishBatch(Long batchId) {
        batchRepository.findById(batchId).ifPresent(b -> {
            b.setStatus(ImageSyncBatch.STATUS_DONE);
            b.setFinishedAt(Instant.now());
            batchRepository.save(b);
        });
    }

    /** 推送 imageSyncProgress SSE 事件（发起管理员；丢帧不影响库内状态） */
    private void pushEvent(Long userId, Long batchId, String instanceNumber,
                           String status, Integer percent, String text, String error) {
        Map<String, Object> event = new HashMap<>();
        event.put("batchId", batchId);
        event.put("instanceNumber", instanceNumber);
        event.put("status", status);
        event.put("percent", percent == null ? 0 : percent);
        if (text != null) {
            event.put("text", text);
        }
        if (error != null) {
            event.put("error", error);
        }
        sseService.pushToUser(userId, "imageSyncProgress", event);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    void shutdownPool() {
        dispatchPool.shutdownNow();
    }
}
