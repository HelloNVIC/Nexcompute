package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.filetransfer.FileTransferService;
import com.nexcompute.management.filetransfer.TransferProgress;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 存储池服务（任务 8.2、8.4、8.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoragePoolService {

    private final StoragePoolRepository poolRepository;
    private final StoragePoolShareRepository shareRepository;
    private final StoragePoolMigrationRepository migrationRepository;
    private final PhysicalInstanceRepository instanceRepository;
    private final UserRepository userRepository;
    private final ContainerRepository containerRepository;
    private final AgentCommandService agentCommandService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final NexcomputeProperties properties;
    private final ObjectMapper objectMapper;
    private final FileTransferService fileTransferService;

    /**
     * 创建存储池（任务 8.2）
     * 命名格式：物理机编号-工号/学号-项目名
     */
    @Audited(action = "STORAGE_POOL_CREATE", targetType = "STORAGE_POOL", targetIdExpr = "#result.id")
    @Transactional
    public StoragePool createPool(Long instanceId, String projectName) {
        Long userId = SecurityUtils.getCurrentUserId();
        PhysicalInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        // 8.1 前置校验：受控端未设置存储池根目录则拒绝建池（根目录状态经心跳落库 storageRoot）
        if (instance.getStorageRoot() == null || instance.getStorageRoot().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "受控端未设置存储池根目录，请先在受控端设置根目录");
        }
        // 离线机器不可建池（复核反馈）：storage.create_dir 无法送达，且先落库会留下无路径的半成品池
        if (!agentCommandService.isAgentConnected(instance.getInstanceNumber())) {
            throw new BusinessException(ErrorCode.INSTANCE_OFFLINE, "物理实例不在线，无法创建存储池");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (poolRepository.existsByInstanceIdAndOwnerIdAndProjectName(instanceId, userId, projectName)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该项目名存储池已存在");
        }

        String poolName = instance.getInstanceNumber() + "-" + user.getStudentId() + "-" + projectName;

        StoragePool pool = StoragePool.builder()
                .poolName(poolName)
                .projectName(projectName)
                .ownerId(userId)
                .instanceId(instanceId)
                .instanceNumber(instance.getInstanceNumber())
                .userStudentId(user.getStudentId())
                .status("ACTIVE")
                .build();
        pool = poolRepository.save(pool);

        // 下发受控端创建目录（任务 8.3）
        Map<String, Object> payload = new HashMap<>();
        payload.put("instanceNumber", instance.getInstanceNumber());
        payload.put("studentId", user.getStudentId());
        payload.put("projectName", projectName);
        payload.put("poolName", poolName);

        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "storage.create_dir", payload, 30000);
        if (result != null && result.isSuccess() && result.getOutput() != null) {
            pool.setPoolPath(result.getOutput().trim());
            pool = poolRepository.save(pool);
        }

        return pool;
    }

    /** 查看用户可访问的存储池（自有 + 被共享），并计算离线派生标识 */
    public List<StoragePool> listAccessible() {
        Long userId = SecurityUtils.getCurrentUserId();
        UserRole role = SecurityUtils.getCurrentRole();
        // platform-refinements #1：管理员可见所有用户的存储池
        if (role == UserRole.ADMIN) {
            List<StoragePool> all = poolRepository.findAll();
            computeOffline(all);
            return all;
        }
        Map<Long, StoragePool> byId = new LinkedHashMap<>();
        for (StoragePool p : poolRepository.findByOwnerId(userId)) byId.put(p.getId(), p);
        // 被共享
        for (StoragePoolShare s : shareRepository.findBySharedToUserId(userId)) {
            poolRepository.findById(s.getPoolId()).ifPresent(p -> byId.put(p.getId(), p));
        }
        // platform-refinements #7：导师可见本课题组所有学生的存储池
        if (role == UserRole.MENTOR) {
            User mentor = userRepository.findById(userId).orElse(null);
            if (mentor != null && mentor.getGroupId() != null) {
                List<Long> studentIds = userRepository.findByGroupId(mentor.getGroupId()).stream()
                        .map(User::getId).toList();
                if (!studentIds.isEmpty()) {
                    for (StoragePool p : poolRepository.findByOwnerIdIn(studentIds)) byId.put(p.getId(), p);
                }
            }
        }
        List<StoragePool> result = new ArrayList<>(byId.values());
        computeOffline(result);
        return result;
    }

    /**
     * 计算离线派生标识（不写入存储状态）。
     * 池离线 = 所属物理实例离线 OR poolPath 缺失/无效。
     * 离线与 ACTIVE/MIGRATING/MIGRATED 共存（迁移中且离线同时展示两标识）。
     */
    private void computeOffline(List<StoragePool> pools) {
        if (pools.isEmpty()) {
            return;
        }
        Set<Long> instanceIds = pools.stream()
                .map(StoragePool::getInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PhysicalInstance> instanceMap = instanceIds.isEmpty()
                ? Map.of()
                : instanceRepository.findAllById(instanceIds).stream()
                .collect(Collectors.toMap(PhysicalInstance::getId, i -> i));
        for (StoragePool pool : pools) {
            PhysicalInstance instance = instanceMap.get(pool.getInstanceId());
            boolean offline = instance == null
                    || !instance.isOnline()
                    || pool.getPoolPath() == null
                    || pool.getPoolPath().isBlank();
            pool.setOffline(offline);
        }
    }

    /**
     * 共享存储池给其他用户（任务 8.4）
     */
    @Audited(action = "STORAGE_POOL_SHARE", targetType = "STORAGE_POOL", targetIdExpr = "#poolId")
    @Transactional
    public void sharePool(Long poolId, Long targetUserId) {
        StoragePool pool = getPoolAndCheckOwnership(poolId);
        if (!shareRepository.existsByPoolIdAndSharedToUserId(poolId, targetUserId)) {
            shareRepository.save(StoragePoolShare.builder()
                    .poolId(poolId)
                    .sharedToUserId(targetUserId)
                    .build());
            // 通知被共享方（任务 13.7）
            notificationService.notify(targetUserId, NotificationType.STORAGE_POOL, poolId,
                    "存储池已共享给你：" + pool.getPoolName(), "共享者已授权你访问此存储池");
            log.info("[StoragePool] 共享: pool={} -> user={}", poolId, targetUserId);
        }
    }

    /**
     * 撤销共享（任务 8.4）
     * 若有运行容器使用此池（被共享方的容器），提示具体容器与联系方式
     */
    public RevokeShareResult revokeShare(Long poolId, Long targetUserId) {
        StoragePool pool = getPoolAndCheckOwnership(poolId);

        // 检查被共享方是否有运行中容器使用此池
        List<Container> runningContainers = containerRepository.findByStoragePoolIdAndStatus(poolId, "RUNNING")
                .stream()
                .filter(c -> c.getOwnerId().equals(targetUserId))
                .toList();

        if (!runningContainers.isEmpty()) {
            User sharedUser = userRepository.findById(targetUserId).orElse(null);
            return RevokeShareResult.blocked(poolId, targetUserId, runningContainers, sharedUser);
        }

        // 无运行容器，撤销成功
        shareRepository.findByPoolId(poolId).stream()
                .filter(s -> s.getSharedToUserId().equals(targetUserId))
                .findFirst()
                .ifPresent(shareRepository::delete);

        log.info("[StoragePool] 撤销共享: pool={} -> user={}", poolId, targetUserId);
        return RevokeShareResult.success(poolId, targetUserId);
    }

    /**
     * 迁移存储池（任务 8.5）
     * 前置检查无运行容器 -> 调度中转传输 -> 断点续传 -> 确认删除
     */
    @Audited(action = "STORAGE_POOL_MIGRATE", targetType = "STORAGE_POOL", targetIdExpr = "#poolId")
    @Transactional
    public StoragePoolMigration migratePool(Long poolId, Long targetInstanceId) {
        StoragePool pool = getPoolAndCheckOwnership(poolId);
        requirePoolInstanceOnline(pool);
        PhysicalInstance targetInstance = instanceRepository.findById(targetInstanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        // 前置校验：目标受控端未设置存储池根目录则拒绝迁移（与建池一致，根目录状态经心跳落库 storageRoot）
        if (targetInstance.getStorageRoot() == null || targetInstance.getStorageRoot().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标受控端未设置存储池根目录，请先在受控端设置根目录");
        }

        // 禁止迁移到同一物理机（无意义，且会触发源/目标路径冲突）
        if (targetInstanceId.equals(pool.getInstanceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能迁移到同一物理机");
        }

        // 前置检查：无运行容器使用此池
        List<Container> running = containerRepository.findByStoragePoolIdAndStatus(poolId, "RUNNING");
        if (!running.isEmpty()) {
            throw new BusinessException(ErrorCode.STORAGE_POOL_IN_USE,
                    "有 " + running.size() + " 个运行中容器使用此存储池，请先停止");
        }

        String transferId = UUID.randomUUID().toString().replace("-", "");
        StoragePoolMigration migration = StoragePoolMigration.builder()
                .poolId(poolId)
                .sourceInstanceId(pool.getInstanceId())
                .targetInstanceId(targetInstanceId)
                .transferId(transferId)
                .status("PENDING")
                .initiatedBy(SecurityUtils.getCurrentUserId())
                .build();
        migration = migrationRepository.save(migration);

        // 标记池为迁移中
        pool.setStatus("MIGRATING");
        poolRepository.save(pool);

        // 异步执行迁移（源端上传 -> 管理端中转 -> 目标端写入）
        // 实际迁移由受控端通过 file-transfer 执行，此处下发迁移命令
        Map<String, Object> sourcePayload = new HashMap<>();
        sourcePayload.put("transferId", transferId);
        sourcePayload.put("poolPath", pool.getPoolPath());
        sourcePayload.put("poolName", pool.getPoolName());
        sourcePayload.put("phase", "upload");

        agentCommandService.fireAndForget(pool.getInstanceNumber(),
                "storage.migration_upload", sourcePayload);

        log.info("[StoragePool] 迁移已启动: pool={} {} -> {}", poolId, pool.getInstanceNumber(), targetInstance.getInstanceNumber());
        // email-notification 5.5：存储池迁移发起后异步通知所有者
        sendPoolMigratedEmail(pool, targetInstance);
        return migration;
    }

    /** 存储池迁移邮件：sourceHost/targetHost 用源/目标物理机编号，status=迁移中。 */
    private void sendPoolMigratedEmail(StoragePool pool, PhysicalInstance targetInstance) {
        if (pool.getOwnerId() == null) return;
        User owner = userRepository.findById(pool.getOwnerId()).orElse(null);
        if (owner == null || owner.getEmail() == null || owner.getEmail().isBlank()) return;
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("operatorName", emailService.resolveOperatorName());
        ctx.put("time", Instant.now());
        ctx.put("poolName", pool.getPoolName());
        ctx.put("sourceHost", pool.getInstanceNumber());
        ctx.put("targetHost", targetInstance != null ? targetInstance.getInstanceNumber() : "");
        ctx.put("status", "迁移中");
        emailService.sendAt(EmailTrigger.STORAGE_POOL_MIGRATED, owner, ctx);
    }

    /** 迁移完成确认删除源池 */
    @Audited(action = "STORAGE_POOL_MIGRATE_CONFIRM", targetType = "STORAGE_POOL", targetIdExpr = "#poolId")
    @Transactional
    public void confirmMigration(Long poolId) {
        StoragePool pool = getPoolAndCheckOwnership(poolId);
        requirePoolInstanceOnline(pool);
        StoragePoolMigration migration = migrationRepository.findByPoolId(poolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "迁移记录不存在"));

        if (!"COMPLETED".equals(migration.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "迁移尚未完成，无法确认");
        }

        // 下发源端删除原池数据
        Map<String, Object> payload = Map.of("poolPath", pool.getPoolPath(), "phase", "delete_source");
        agentCommandService.fireAndForget(pool.getInstanceNumber(), "storage.migration_cleanup", payload);

        migration.setStatus("CONFIRMED");
        migration.setCompletedAt(Instant.now());
        migrationRepository.save(migration);

        pool.setStatus("MIGRATED");
        poolRepository.save(pool);
        log.info("[StoragePool] 迁移已确认，源池数据已删除: {}", poolId);

        // 通知：迁移完成（任务 13.7）
        notificationService.notify(pool.getOwnerId(), NotificationType.STORAGE_POOL, poolId,
                "存储池迁移已完成：" + pool.getPoolName(), "源池数据已删除");
    }

    /**
     * 删除存储池（platform-refinements 7.2）。
     * <p>普通删除：前置检查实例在线 + 无运行中容器使用此池（有则拒绝，提示先停止）。
     * <p>强制删除（force=true，仅管理员）：跳过在线检查与运行容器检查，仅清理管理端元数据与共享/迁移关系；
     * 受控端磁盘数据 best-effort 清理（离线则跳过并告警，需实例恢复后手动清理）。用于物理实例永久离线场景。
     */
    @Audited(action = "STORAGE_POOL_DELETE", targetType = "STORAGE_POOL", targetIdExpr = "#poolId")
    @Transactional
    public void deletePool(Long poolId, boolean force) {
        StoragePool pool = getPoolAndCheckOwnership(poolId);
        if (force) {
            if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
                throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可强制删除存储池");
            }
            log.warn("[StoragePool] 管理员强制删除存储池（实例可能离线，磁盘数据需手动清理）: {} ({})",
                    poolId, pool.getPoolName());
        } else {
            requirePoolInstanceOnline(pool);
            // 前置检查：无运行中容器使用此池
            List<Container> running = containerRepository.findByStoragePoolIdAndStatus(poolId, "RUNNING");
            if (!running.isEmpty()) {
                throw new BusinessException(ErrorCode.STORAGE_POOL_IN_USE,
                        "有 " + running.size() + " 个运行中容器使用此存储池，请先停止相关容器");
            }
        }

        // 下发受控端删除目录（best-effort：离线或失败仅告警，元数据仍删除）
        if (pool.getPoolPath() != null && !pool.getPoolPath().isBlank()
                && agentCommandService.isAgentConnected(pool.getInstanceNumber())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("poolPath", pool.getPoolPath());
            AgentCommandResult result = agentCommandService.sendCommand(
                    pool.getInstanceNumber(), "storage.delete_dir", payload, 30000);
            if (result == null || !result.isSuccess()) {
                log.warn("[StoragePool] 受控端删除目录失败（元数据仍删除）: pool={} err={}", poolId,
                        result != null ? result.getError() : "受控端无响应");
            }
        } else {
            log.warn("[StoragePool] 受控端离线或无 poolPath，跳过目录删除: pool={}", poolId);
        }

        // 删除共享关系与迁移记录
        shareRepository.deleteByPoolId(poolId);
        migrationRepository.findByPoolId(poolId).ifPresent(migrationRepository::delete);
        poolRepository.delete(pool);
        log.info("[StoragePool] 存储池已删除: {} ({}){}", poolId, pool.getPoolName(), force ? "（强制）" : "");
    }

    // ===== 存储池文件管理（platform-refinements #3：浏览/打包下载/上传）=====

    /** 列出存储池目录内容（platform-refinements #3） */
    public List<Map<String, Object>> listFiles(Long poolId, String subPath) {
        StoragePool pool = checkFileAccess(poolId);
        requirePoolInstanceOnline(pool);
        if (pool.getPoolPath() == null || pool.getPoolPath().isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "存储池路径未就绪");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("poolPath", pool.getPoolPath());
        payload.put("subPath", subPath == null ? "" : subPath);
        AgentCommandResult result = agentCommandService.sendCommand(
                pool.getInstanceNumber(), "storage.list_files", payload, 30000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                    result != null ? result.getError() : "受控端无响应");
        }
        return parseEntries(result.getOutput());
    }

    /** 发起打包下载（platform-refinements #3：异步--受控端 tar 后经 file-transfer 回传，前端轮询进度） */
    public String archiveStart(Long poolId, String subPath) {
        StoragePool pool = checkFileAccess(poolId);
        if (!agentCommandService.isAgentConnected(pool.getInstanceNumber())) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "受控端离线");
        }
        String transferId = "storage-dl-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = new HashMap<>();
        payload.put("poolPath", pool.getPoolPath());
        payload.put("subPath", subPath == null ? "" : subPath);
        payload.put("transferId", transferId);
        boolean sent = agentCommandService.fireAndForget(pool.getInstanceNumber(), "storage.archive", payload);
        if (!sent) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "受控端无响应");
        }
        return transferId;
    }

    /** 查询打包下载回传进度（platform-refinements #3） */
    public Map<String, Object> downloadProgress(String transferId) {
        TransferProgress p = fileTransferService.getProgress(transferId);
        if (p == null) {
            return Map.of("status", "pending", "percent", 0);
        }
        int percent = p.getTotalChunks() > 0
                ? (int) Math.round(p.getDoneChunks() * 100.0 / p.getTotalChunks()) : 0;
        return Map.of(
                "status", p.getStatus() != null ? p.getStatus() : "pending",
                "percent", percent,
                "fileName", p.getFileName() != null ? p.getFileName() : "",
                "totalBytes", p.getTotalBytes(),
                "doneBytes", p.getDoneBytes());
    }

    /** 下载已就绪的打包文件（platform-refinements #3：进度 100% 后前端调用） */
    public void downloadFile(String transferId, HttpServletResponse response) throws IOException {
        TransferProgress p = fileTransferService.getProgress(transferId);
        if (p == null || !"completed".equals(p.getStatus()) || p.getFileName() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "打包文件未就绪");
        }
        Path staged = Paths.get(properties.getStorage().getMigrationStagingDir(),
                transferId + "_" + p.getFileName());
        if (!Files.exists(staged)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "打包文件不存在");
        }
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + p.getFileName() + "\"");
        response.setContentLengthLong(Files.size(staged));
        try (InputStream in = Files.newInputStream(staged); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException e) { log.warn("[StoragePool] 删除临时打包失败: {}", e.getMessage()); }
        }
    }

    /** 上传文件至存储池（platform-refinements #3）：暂存 -> 受控端 file-transfer 下载写入 */
    @Transactional
    public void uploadFile(Long poolId, String subPath, String relativePath, MultipartFile file) throws IOException {
        StoragePool pool = checkFileAccess(poolId);
        requirePoolInstanceOnline(pool);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        String staging = properties.getStorage().getMigrationStagingDir();
        Files.createDirectories(Paths.get(staging));
        String stagedName = "upload-" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path staged = Paths.get(staging, stagedName);
        file.transferTo(staged.toFile());

        String transferId = "storage-ul-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = new HashMap<>();
        payload.put("poolPath", pool.getPoolPath());
        payload.put("subPath", subPath == null ? "" : subPath);
        payload.put("relativePath", relativePath != null ? relativePath : file.getOriginalFilename());
        payload.put("sourcePath", staged.toString());
        payload.put("transferId", transferId);

        try {
            AgentCommandResult result = agentCommandService.sendCommand(
                    pool.getInstanceNumber(), "storage.upload_file", payload, 600000);
            if (result == null || !result.isSuccess()) {
                throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                        result != null ? result.getError() : "受控端无响应");
            }
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException e) { log.warn("[StoragePool] 删除暂存失败: {}", e.getMessage()); }
        }
    }

    /** 文件操作访问校验：所有者 / 被共享 / 管理员 */
    private StoragePool checkFileAccess(Long poolId) {
        Long userId = SecurityUtils.getCurrentUserId();
        StoragePool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_POOL_NOT_FOUND));
        if (SecurityUtils.getCurrentRole() == UserRole.ADMIN) return pool;
        if (pool.getOwnerId().equals(userId)) return pool;
        if (shareRepository.existsByPoolIdAndSharedToUserId(poolId, userId)) return pool;
        throw new BusinessException(ErrorCode.PERMISSION_DENIED, "无权访问此存储池");
    }

    /** 校验存储池所在实例在线（platform-refinements：离线时所有操作不可进行） */
    private void requirePoolInstanceOnline(StoragePool pool) {
        if (pool.getInstanceNumber() == null) return;
        if (!agentCommandService.isAgentConnected(pool.getInstanceNumber())) {
            throw new BusinessException(ErrorCode.INSTANCE_OFFLINE, "物理实例不在线，操作不可用");
        }
    }

    private List<Map<String, Object>> parseEntries(String output) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (output == null) return result;
        try {
            JsonNode node = objectMapper.readTree(output);
            JsonNode entries = node.path("entries");
            if (entries.isArray()) {
                for (JsonNode e : entries) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.path("name").asText());
                    m.put("isDir", e.path("isDir").asBoolean());
                    m.put("size", e.path("size").asLong());
                    m.put("modTime", e.path("modTime").asText());
                    result.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("[StoragePool] 解析目录列表失败: {}", e.getMessage());
        }
        return result;
    }

    private StoragePool getPoolAndCheckOwnership(Long poolId) {
        StoragePool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_POOL_NOT_FOUND));
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN
                && !pool.getOwnerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅所有者可操作");
        }
        return pool;
    }

    /** 撤销共享结果 */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Builder
    public static class RevokeShareResult {
        private boolean success;
        private boolean blocked;
        private Long poolId;
        private Long targetUserId;
        private List<Container> blockingContainers;
        private String contactInfo; // 被共享方联系方式

        public static RevokeShareResult success(Long poolId, Long userId) {
            return RevokeShareResult.builder().success(true).poolId(poolId).targetUserId(userId).build();
        }

        public static RevokeShareResult blocked(Long poolId, Long userId, List<Container> containers, User sharedUser) {
            String contact = "";
            if (sharedUser != null) {
                contact = String.format("姓名: %s, 邮箱: %s, 手机: %s",
                        sharedUser.getRealName(), sharedUser.getEmail(), sharedUser.getPhone());
            }
            return RevokeShareResult.builder()
                    .success(false).blocked(true)
                    .poolId(poolId).targetUserId(userId)
                    .blockingContainers(containers).contactInfo(contact).build();
        }
    }
}
