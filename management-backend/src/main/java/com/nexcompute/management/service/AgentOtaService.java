package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

/**
 * 受控端 OTA 升级服务（D7）。
 * 管理员上传新版 exe，按实例批量/单独下发 agent.upgrade；
 * 串行下发，单实例失败不阻断其他，写 agent_upgrade_task 状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOtaService {

    private final NexcomputeProperties properties;
    private final PhysicalInstanceRepository instanceRepository;
    private final AgentUpgradeTaskRepository taskRepository;
    private final AgentCommandService agentCommandService;

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
     * 批量/单独升级（D7）：串行向各实例下发 agent.upgrade，单实例失败不阻断其他。
     * payload {version, md5, downloadUrl}，downloadUrl 为 exe 源路径供受控端 file-transfer 下载。
     */
    @Audited(action = "AGENT_UPGRADE", targetType = "PHYSICAL_INSTANCE")
    @Transactional
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

        // 先创建 PENDING 任务
        List<AgentUpgradeTask> tasks = new ArrayList<>();
        for (Long instanceId : instanceIds) {
            PhysicalInstance inst = instanceRepository.findById(instanceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
            AgentUpgradeTask task = AgentUpgradeTask.builder()
                    .instanceId(instanceId)
                    .instanceNumber(inst.getInstanceNumber())
                    .version(safeVersion)
                    .md5(md5)
                    .status("PENDING")
                    .build();
            tasks.add(taskRepository.save(task));
        }

        // 串行下发，单实例失败不阻断其他
        Map<String, Object> payload = Map.of("version", safeVersion, "md5", md5, "downloadUrl", downloadUrl);
        for (AgentUpgradeTask task : tasks) {
            try {
                // 发送升级命令（受控端返回 replacing 后退出，由 updater 替换重启）
                try {
                    agentCommandService.sendCommand(
                            task.getInstanceNumber(), "agent.upgrade", payload,
                            properties.getAgent().getUpgradeCommandTimeoutMs());
                } catch (Exception cmdEx) {
                    // 受控端可能在返回后立即退出致连接断开，不在此判定成败
                    log.warn("[AgentOTA] 发送升级命令异常（受控端可能已退出）: {}", cmdEx.getMessage());
                }
                // D7：等待受控端重启并回传新版本，验证通过才算成功，否则为失败
                boolean ok = waitForVersionUpgrade(task.getInstanceId(), safeVersion,
                        properties.getAgent().getUpgradeVersionWaitTimeoutMs());
                task.setStatus(ok ? "SUCCESS" : "FAILED");
                if (!ok) {
                    task.setError("升级后未在超时内回传目标版本 v" + safeVersion);
                }
            } catch (Exception e) {
                task.setStatus("FAILED");
                task.setError(e.getMessage());
                log.warn("[AgentOTA] 实例 {} 升级异常: {}", task.getInstanceNumber(), e.getMessage());
            }
            task.setFinishedAt(Instant.now());
            taskRepository.save(task);
        }
        return tasks;
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
    private String sanitizeVersion(String version) {
        if (version == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : version.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
