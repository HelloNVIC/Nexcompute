package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.agent.OtaProgressTracker;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.common.FileChecksums;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.AgentUpgradeTask;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.repository.AgentUpgradeTaskRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 受控端 OTA 升级服务（D7）。
 * 管理员上传新版 exe，按实例批量/单独下发 agent.upgrade；
 * 并发同时升级（每实例一线程：下发 -> 等版本回传 -> 落状态），
 * 单实例失败不阻断其他，写 agent_upgrade_task 状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOtaService {

    private final NexcomputeProperties properties;
    private final PhysicalInstanceRepository instanceRepository;
    private final AgentUpgradeTaskRepository taskRepository;
    private final AgentCommandService agentCommandService;
    private final OtaProgressTracker otaProgressTracker;
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTx() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 上传新版受控端 exe，存 ${storage.root}/agent-upgrade/{version}.exe 并记 MD5 */
    @Transactional
    public Map<String, Object> upload(MultipartFile file, String version) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        String safeVersion = sanitizeVersion(version);
        if (safeVersion.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本号为空");
        }
        String dir = properties.getStorage().getAgentUpgradeDir();
        try {
            Files.createDirectories(Paths.get(dir));
            Path target = Paths.get(dir, safeVersion + ".exe");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String md5 = FileChecksums.md5File(target);
            long size = Files.size(target);
            log.info("[AgentOTA] 上传新版 exe: version={} md5={} size={}", safeVersion, md5, size);
            return Map.of(
                    "version", safeVersion,
                    "md5", md5,
                    "size", size,
                    "downloadUrl", target.toString());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存升级 exe 失败: " + e.getMessage());
        }
    }

    /** 列出已上传的版本（扫描 agent-upgrade 目录） */
    public List<Map<String, Object>> listVersions() {
        String dir = properties.getStorage().getAgentUpgradeDir();
        Path dirPath = Paths.get(dir);
        if (!Files.isDirectory(dirPath)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(dirPath)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".exe")) continue;
                String version = name.substring(0, name.length() - 4);
                try {
                    result.add(Map.of(
                            "version", version,
                            "md5", FileChecksums.md5File(p),
                            "size", Files.size(p)));
                } catch (IOException e) {
                    log.warn("[AgentOTA] 读取版本文件失败: {} {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[AgentOTA] 列举版本失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 批量/单独升级（D7）：并发同时升级各实例（每实例一线程），单实例失败不阻断其他。
     * payload {version, md5, downloadUrl}，downloadUrl 为 exe 源路径供受控端 file-transfer 下载。
     */
    @Audited(action = "AGENT_UPGRADE", targetType = "PHYSICAL_INSTANCE")
    public List<AgentUpgradeTask> upgrade(List<Long> instanceIds, String version) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未选择实例");
        }
        String safeVersion = sanitizeVersion(version);
        Path exePath = Paths.get(properties.getStorage().getAgentUpgradeDir(), safeVersion + ".exe");
        if (!Files.exists(exePath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "版本 " + safeVersion + " 的 exe 不存在，请先上传");
        }
        String md5;
        String downloadUrl = exePath.toString();
        try {
            md5 = FileChecksums.md5File(exePath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算 exe MD5 失败");
        }

        // platform-audit-logging-ux：先在独立事务中创建并提交 PENDING 任务，
        // 使前端"升级任务记录"在升级开始时立即可见（不阻塞于下方 waitForVersionUpgrade 的长耗时等待）
        final String md5Final = md5;
        List<AgentUpgradeTask> tasks = transactionTemplate.execute(status -> {
            List<AgentUpgradeTask> list = new ArrayList<>();
            for (Long instanceId : instanceIds) {
                PhysicalInstance inst = instanceRepository.findById(instanceId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
                AgentUpgradeTask task = AgentUpgradeTask.builder()
                        .instanceId(instanceId)
                        .instanceNumber(inst.getInstanceNumber())
                        .version(safeVersion)
                        .md5(md5Final)
                        .status("PENDING")
                        .build();
                list.add(taskRepository.save(task));
            }
            return list;
        });

        // 并发同时升级：每实例一线程并行执行 下发 -> 等版本回传 -> 落状态，
        // 全部结束（成功/失败/超时）后统一返回（前端 upgrade 请求 timeout=0 不限时；
        // 单实例耗时上限 = 命令超时 + 版本等待超时，与实例数无关）。
        Map<String, Object> payload = Map.of("version", safeVersion, "md5", md5, "downloadUrl", downloadUrl);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (AgentUpgradeTask task : tasks) {
                futures.add(pool.submit(() -> upgradeOneInstance(task, payload, safeVersion)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[AgentOTA] 等待升级线程被中断");
                } catch (ExecutionException e) {
                    log.warn("[AgentOTA] 升级线程异常: {}", e.getCause() == null ? e : e.getCause().getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return tasks;
    }

    /**
     * 单实例升级（并发 worker）：下发 agent.upgrade（带进度追踪）-> 等待版本回传 -> 落最终状态。
     * 异常均吞掉并记入 task（FAILED），不影响其他实例。
     * 原 D6.2 失败分类逻辑不变：命令下发失败 / 等待版本回传超时。
     */
    private void upgradeOneInstance(AgentUpgradeTask task, Map<String, Object> payload, String safeVersion) {
        try {
            boolean commandDispatched = true;
            String dispatchError = null;
            String commandId = null;
            try {
                var dispatch = agentCommandService.sendCommandWithId(
                        task.getInstanceNumber(), "agent.upgrade", payload,
                        properties.getAgent().getUpgradeCommandTimeoutMs());
                commandId = dispatch.commandId();
                // D2：注册进度追踪（commandId <-> task/instance/目标版本）
                otaProgressTracker.register(commandId, task.getId(), task.getInstanceId(),
                        task.getInstanceNumber(), safeVersion);
            } catch (Exception cmdEx) {
                // 受控端可能在返回 replacing 后立即退出致连接断开，这种异常不算命令失败
                String msg = String.valueOf(cmdEx.getMessage());
                if (msg != null && (msg.contains("replacing") || msg.contains("abrupt")
                        || msg.contains("reset") || msg.contains("broken"))) {
                    log.info("[AgentOTA] 受控端返回后退出致连接断开（正常）: {}", msg);
                } else {
                    // 命令本身未成功送达/受控端未返回 replacing -> 命令下发失败
                    commandDispatched = false;
                    dispatchError = "升级命令下发失败：" + msg;
                    log.warn("[AgentOTA] 升级命令下发失败: {}", msg);
                }
            }

            boolean ok = false;
            if (commandDispatched) {
                // D1：等待受控端重启并回传目标版本，心跳回传目标版本即 SUCCESS
                ok = waitForVersionUpgrade(task.getInstanceId(), safeVersion,
                        properties.getAgent().getUpgradeVersionWaitTimeoutMs());
                if (!ok && commandId != null) {
                    otaProgressTracker.onTimeout(commandId);
                }
            }
            if (commandId != null) {
                otaProgressTracker.finish(commandId);
            }
            task.setStatus(ok ? "SUCCESS" : "FAILED");
            if (!ok) {
                task.setError(commandDispatched
                        ? "等待版本回传超时：升级后未在 " + (properties.getAgent().getUpgradeVersionWaitTimeoutMs() / 1000)
                                + "s 内回传目标版本 " + safeVersion
                        : dispatchError);
            }
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setError(e.getMessage());
            log.warn("[AgentOTA] 实例 {} 升级异常: {}", task.getInstanceNumber(), e.getMessage());
        }
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);
    }

    /**
     * 等待受控端重启并经心跳回传目标版本，超时则判失败（D7）。
     * 受控端升级后自动重启，心跳上报 agentVersion；轮询实例 agentVersion 是否等于目标版本。
     */
    private boolean waitForVersionUpgrade(Long instanceId, String targetVersion, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            PhysicalInstance inst = instanceRepository.findById(instanceId).orElse(null);
            if (inst != null && targetVersion.equals(inst.getAgentVersion())) {
                return true;
            }
        }
        return false;
    }

    public List<AgentUpgradeTask> listTasks() {
        return taskRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<AgentUpgradeTask> listTasksByInstance(Long instanceId) {
        return taskRepository.findByInstanceIdOrderByCreatedAtDesc(instanceId);
    }

    /** 删除已上传的版本 exe（D7 增删改查：删） */
    @Transactional
    public void deleteVersion(String version) {
        String safeVersion = sanitizeVersion(version);
        if (safeVersion.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本号为空");
        }
        Path exePath = Paths.get(properties.getStorage().getAgentUpgradeDir(), safeVersion + ".exe");
        try {
            Files.deleteIfExists(exePath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除版本文件失败: " + e.getMessage());
        }
        log.info("[AgentOTA] 删除版本 exe: {}", safeVersion);
    }

    /** 版本号sanitize：仅允许字母数字 . _ - */
    String sanitizeVersion(String version) {
        if (version == null) return "";
        String trimmed = version.trim();
        // platform-audit-logging-ux：版本号不加 'v' 前缀（受控端心跳上报为纯数字版本，
        // 加 'v' 会导致 waitForVersionUpgrade 比对不匹配误判失败）
        if (trimmed.toLowerCase().startsWith("v") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
