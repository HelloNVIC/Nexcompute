package com.nexcompute.management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.dto.ConnectionInfo;
import com.nexcompute.management.dto.CreateContainerRequest;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 容器生命周期服务（任务 10.3、10.5、10.6、10.8、10.9）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerService {

    private final ContainerRepository containerRepository;
    private final PhysicalInstanceRepository instanceRepository;
    private final StoragePoolRepository poolRepository;
    private final StoragePoolShareRepository shareRepository;
    private final PortAllocationService portAllocationService;
    private final AgentCommandService agentCommandService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final ImageService imageService;
    private final ResourceQuotaService quotaService;
    private final MachineAllocationRepository machineAllocationRepository;
    private final ContainerShareRepository containerShareRepository;

    /**
     * 创建容器（任务 10.3）
     */
    @Audited(action = "CONTAINER_CREATE", targetType = "CONTAINER", targetIdExpr = "#result.id")
    @Transactional
    public Container createContainer(CreateContainerRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PhysicalInstance instance = instanceRepository.findById(request.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));

        // 镜像限选校验（任务 2.3）：imageRef 必须命中管理端已配置镜像，拒绝自由文本
        ImageMetadata image = imageService.resolveVisibleImage(request.getImageRef());

        // 校验存储池权限（任务 8.2 spec: 只能选自有或被共享的池）
        if (request.getStoragePoolId() != null) {
            validateStoragePoolAccess(request.getStoragePoolId(), userId);
        }

        // 资源限制配额校验（任务 2.6）：不超过有效配额（用户->课题组->主机容量回退）
        validateResourceQuota(request, userId, instance.getId());

        // platform-refinements 8.3：导师为该学生在该实例设了单容器内存上限时，容器内存不得超过
        validatePerContainerMemoryLimit(request, userId, instance.getId());

        // 创建容器前按需分发镜像 tar（任务 2.8 / 3.5）：受控端检查本地是否已持有，
        // 已持有则跳过，否则经 file-transfer 下载并 docker load；失败中止创建（不分配端口，避免泄漏）。
        ensureImageLoaded(instance, image);

        // platform-refinements #6：查询受控端 docker 实际占用端口，避免与泄漏/系统外容器冲突
        Set<Integer> dockerUsedPorts = queryDockerUsedPorts(instance.getInstanceNumber());

        // 分配端口（任务 10.2）：SSH 22 + 自定义端口
        List<Integer> containerPorts = new ArrayList<>();
        containerPorts.add(22); // SSH
        if (request.getContainerPorts() != null) {
            containerPorts.addAll(request.getContainerPorts());
        }

        List<PortAllocation> allocations = new ArrayList<>();
        List<Long> allocationIds = new ArrayList<>();
        for (int cp : containerPorts) {
            PortAllocation a = portAllocationService.allocatePort(instance.getId(), cp, dockerUsedPorts);
            allocations.add(a);
            allocationIds.add(a.getId());
        }

        // 生成 docker run 命令参数（任务 10.3 spec：含资源限制与软限制 env）
        Map<String, Object> payload = buildDockerRunPayload(request, allocations, instance);

        // 下发受控端执行（任务 10.4）
        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "container.create", payload, 120000);
        if (result == null || !result.isSuccess()) {
            // 创建失败，按 id 释放端口（platform-refinements #6：原 releaseByContainer(null) 是空操作致泄漏）
            portAllocationService.releaseByIds(allocationIds);
            throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                    result != null ? result.getError() : "受控端无响应");
        }

        // 解析受控端返回的 docker ID
        String dockerId = parseOutputField(result.getOutput(), "containerId");

        // 容器命名：学号-项目名-随机串（任务 5）
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String studentId = owner.getStudentId() != null ? owner.getStudentId() : "user" + userId;
        String projectName = request.getProjectName() != null ? request.getProjectName() : "default";
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        String containerName = studentId + "-" + projectName + "-" + randomSuffix;

        Container container = Container.builder()
                .name(containerName)
                .ownerId(userId)
                .instanceId(instance.getId())
                .instanceNumber(instance.getInstanceNumber())
                .imageRef(request.getImageRef())
                .imageId(image.getId())
                .storagePoolId(request.getStoragePoolId())
                .projectName(request.getProjectName())
                .formSnapshot(request.getFormSnapshot())
                .cpuLimit(request.getCpuLimit())
                .memoryLimit(request.getMemoryLimit())
                .gpuMemoryLimit(request.getGpuMemoryLimit())
                .shmSize(request.getShmSize())
                .portMappings(serializePortMappings(allocations))
                .sshPassword(request.getSshPassword())
                .dockerId(dockerId)
                .remark(request.getRemark())
                .status("RUNNING")
                .build();
        container = containerRepository.save(container);

        // 绑定端口到容器
        portAllocationService.bindContainer(allocationIds, container.getId());

        // 通知：容器已创建（任务 13.7）
        notificationService.notify(userId, NotificationType.CONTAINER, container.getId(),
                "容器已创建：" + container.getName(), "镜像：" + request.getImageRef());

        return container;
    }

    /**
     * 创建容器前按需分发镜像（任务 2.8 / 3.5）。
     * 复用受控端既有 image.load：受控端检查本地是否已持有该镜像，已持有则跳过，
     * 否则经 file-transfer 下载 tar 并 docker load。分发失败中止创建。
     */
    private void ensureImageLoaded(PhysicalInstance instance, ImageMetadata image) {
        if (image.getTarPath() == null || image.getTarPath().isBlank()) {
            // 无 tar 路径（如 Dockerfile 构建产物依赖受控端本地）则跳过分发
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourcePath", image.getTarPath());
        payload.put("transferId", UUID.randomUUID().toString().replace("-", ""));
        payload.put("imageRef", image.getRef());
        AgentCommandResult loadResult = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "image.load", payload, 600000);
        if (loadResult == null || !loadResult.isSuccess()) {
            throw new BusinessException(ErrorCode.IMAGE_TRANSFER_FAILED,
                    "镜像分发失败：" + (loadResult != null ? loadResult.getError() : "受控端无响应"));
        }
        // 受控端返回 already_exists / loaded 均视为成功
    }

    /**
     * 资源限制配额校验（任务 2.6）。
     * 各维度独立：仅当有效配额该维度设了上限且请求也提供了值时校验，超限拒绝。
     * 单位：cpuLimit(核)/memoryLimit(字节)/gpuMemoryLimit(MB)/shmSize(字节)；
     * 配额 maxMemoryMb/maxShmMb 为 MB，需换算。
     */
    private void validateResourceQuota(CreateContainerRequest req, Long userId, Long instanceId) {
        ResourceQuotaService.EffectiveQuota quota = quotaService.getEffectiveQuota(userId, instanceId);
        if (req.getCpuLimit() != null && quota.getMaxCpuCores() != null
                && req.getCpuLimit() > quota.getMaxCpuCores()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                    "CPU 超出配额上限 " + quota.getMaxCpuCores() + " 核");
        }
        if (req.getMemoryLimit() != null && quota.getMaxMemoryMb() != null) {
            long reqMb = req.getMemoryLimit() / (1024 * 1024);
            if (reqMb > quota.getMaxMemoryMb()) {
                throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                        "内存超出配额上限 " + quota.getMaxMemoryMb() + " MB");
            }
        }
        if (req.getGpuMemoryLimit() != null && quota.getMaxGpuMemoryMb() != null
                && req.getGpuMemoryLimit() > quota.getMaxGpuMemoryMb()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                    "GPU 显存超出配额上限 " + quota.getMaxGpuMemoryMb() + " MB");
        }
        if (req.getShmSize() != null && quota.getMaxShmMb() != null) {
            long reqMb = req.getShmSize() / (1024 * 1024);
            if (reqMb > quota.getMaxShmMb()) {
                throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                        "SHM 超出配额上限 " + quota.getMaxShmMb() + " MB");
            }
        }
    }

    /**
     * 单容器内存上限校验（platform-refinements 8.3）。
     * 若导师为该学生在该实例设了 perContainerMemoryMb，请求内存不得超过。
     */
    private void validatePerContainerMemoryLimit(CreateContainerRequest req, Long userId, Long instanceId) {
        Integer limitMb = machineAllocationRepository.findByInstanceIdAndUserId(instanceId, userId)
                .map(MachineAllocation::getPerContainerMemoryMb)
                .orElse(null);
        if (limitMb == null || req.getMemoryLimit() == null) return;
        long reqMb = req.getMemoryLimit() / (1024 * 1024);
        if (reqMb > limitMb) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                    "内存超出导师设定的单容器上限 " + limitMb + " MB");
        }
    }

    /**
     * 生成连接信息（任务 10.5）
     * 直连模式：物理机 IP + 宿主端口
     */
    public ConnectionInfo getConnectionInfo(Long containerId) {
        Container container = getContainer(containerId);
        PhysicalInstance instance = instanceRepository.findById(container.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));

        List<PortAllocation> allocations = portAllocationService.getByContainer(containerId);

        ConnectionInfo.SshConnection ssh = null;
        List<ConnectionInfo.AppConnection> apps = new ArrayList<>();

        for (PortAllocation a : allocations) {
            if (a.getContainerPort() == 22) {
                ssh = ConnectionInfo.SshConnection.builder()
                        .host(instance.getIpAddress())
                        .port(a.getHostPort())
                        .build();
            } else {
                apps.add(ConnectionInfo.AppConnection.builder()
                        .name("port-" + a.getContainerPort())
                        .containerPort(a.getContainerPort())
                        .hostPort(a.getHostPort())
                        .url(instance.getIpAddress() + ":" + a.getHostPort())
                        .build());
            }
        }

        return ConnectionInfo.builder()
                .ssh(ssh)
                .apps(apps)
                .mode(instance.getConnectMode())
                .build();
    }

    /**
     * 容器生命周期（任务 10.6）：start/stop/restart/delete
     * 删除前必须先停止运行（任务 5），删除后从列表移除不显示
     */
    @Audited(action = "CONTAINER_LIFECYCLE", targetType = "CONTAINER", targetIdExpr = "#containerId")
    public AgentCommandResult lifecycle(Long containerId, String action) {
        Container container = getContainer(containerId);
        requireInstanceOnline(container); // platform-refinements：离线时操作不可用

        // 删除前校验：必须已停止（任务 5）
        if ("rm".equals(action) && container.isRunning()) {
            throw new BusinessException(ErrorCode.CONFLICT, "删除容器前必须先停止运行");
        }

        PhysicalInstance instance = instanceRepository.findById(container.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));

        Map<String, Object> payload = new HashMap<>();
        payload.put("containerId", container.getDockerId());
        payload.put("action", action);

        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "container." + action, payload, 60000);

        if (result != null && result.isSuccess()) {
            String newStatus = switch (action) {
                case "start" -> "RUNNING";
                case "stop" -> "STOPPED";
                case "restart" -> "RUNNING";
                case "rm" -> "REMOVED";
                default -> container.getStatus();
            };

            if ("rm".equals(action)) {
                // 删除：从数据库移除，列表不再显示（任务 5）
                portAllocationService.releaseByContainer(containerId);
                containerRepository.delete(container);
            } else {
                container.setStatus(newStatus);
                containerRepository.save(container);
            }

            // 通知：容器变动（任务 13.7）
            String actionText = switch (action) {
                case "start" -> "已启动";
                case "stop" -> "已停止";
                case "restart" -> "已重启";
                case "rm" -> "已删除";
                default -> action;
            };
            notificationService.notify(container.getOwnerId(), NotificationType.CONTAINER,
                    container.getId(), "容器" + actionText + "：" + container.getName(), "");
        }

        return result;
    }

    /**
     * SSH 密码重置（任务 10.8）：即时 docker exec 更新运行中容器密码
     */
    @Audited(action = "CONTAINER_RESET_SSH", targetType = "CONTAINER", targetIdExpr = "#containerId")
    @Transactional
    public void resetSshPassword(Long containerId, String newPassword) {
        Container container = getContainer(containerId);
        requireInstanceOnline(container);
        if (!container.isRunning()) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅运行中容器可重置密码");
        }
        PhysicalInstance instance = instanceRepository.findById(container.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));

        Map<String, Object> payload = new HashMap<>();
        payload.put("containerId", container.getDockerId());
        payload.put("password", newPassword);

        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "container.reset_ssh", payload, 30000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                    result != null ? result.getError() : "受控端无响应");
        }

        container.setSshPassword(newPassword);
        containerRepository.save(container);
        log.info("[Container] SSH 密码已重置: {}", container.getName());
    }

    /**
     * 容器提交镜像持久化（platform-refinements 6.3）。
     * 建 UPLOADING 镜像元数据（commit 默认 PRIVATE），下发 image.commit 命令（含 project/note/ownerWorkerId）。
     * 受控端 commit+save 后经 file-transfer 上传 tar，上传完成回调置 READY（6.4），失败置 FAILED。
     */
    @Audited(action = "CONTAINER_COMMIT_IMAGE", targetType = "CONTAINER", targetIdExpr = "#containerId")
    // platform-refinements #3b：不加 @Transactional——registerCommitImage 自带事务并先提交，
    // 否则 sendCommand 阻塞期间镜像未提交，完成回调的 onImageTransfer 读不到镜像 -> IMAGE_NOT_FOUND(404)。
    public ImageMetadata commitContainerImage(Long containerId, String imageName, String imageTag,
                                             String project, String note) {
        Long userId = SecurityUtils.getCurrentUserId();
        Container container = getContainer(containerId);
        requireInstanceOnline(container);
        if (!container.getOwnerId().equals(userId) && SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅所有者可提交镜像");
        }
        PhysicalInstance instance = instanceRepository.findById(container.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        User owner = userRepository.findById(container.getOwnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String workerId = owner.getStudentId() != null ? owner.getStudentId() : "user" + owner.getId();

        String sourceContainer = container.getDockerId() != null ? container.getDockerId() : container.getName();
        ImageMetadata image = imageService.registerCommitImage(
                imageName, imageTag, container.getOwnerId(), sourceContainer,
                project, note, workerId, null, null, null);

        // transferId 编码 imageId，供 file-transfer 完成回调解析置 READY（6.4）
        String transferId = "commit-" + image.getId() + "-" + UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> payload = new HashMap<>();
        payload.put("containerId", container.getDockerId());
        payload.put("imageName", imageName);
        payload.put("imageTag", imageTag != null ? imageTag : "latest");
        payload.put("project", project);
        payload.put("note", note);
        payload.put("ownerWorkerId", workerId);
        payload.put("transferId", transferId);

        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "image.commit", payload, 600000);
        if (result == null || !result.isSuccess()) {
            imageService.markImageFailed(image.getId());
            throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                    result != null ? result.getError() : "受控端无响应");
        }
        return imageService.getImage(image.getId());
    }

    /**
     * 容器可见性过滤（platform-refinements #1/#2 调整）
     * - 管理员：全部
     * - 导师：自己 + 课题组学生
     * - 学生：自己 + 被共享（含限时，过期剔除）
     * 所有返回结果附带 ownerName 与 sharedWith 派生字段。
     */
    public List<Container> listVisible() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();

        List<Container> result = new ArrayList<>();
        if (role == UserRole.ADMIN) {
            result.addAll(containerRepository.findAll());
        } else if (role == UserRole.MENTOR) {
            User mentor = userRepository.findById(userId).orElseThrow();
            if (mentor.getGroupId() == null) {
                result.addAll(containerRepository.findByOwnerId(userId));
            } else {
                List<User> students = userRepository.findByGroupId(mentor.getGroupId());
                List<Long> studentIds = new ArrayList<>(students.stream().map(User::getId).toList());
                studentIds.add(userId);
                result.addAll(containerRepository.findByOwnerIdIn(studentIds));
            }
        } else {
            // 学生：自己 + 被共享且未过期
            result.addAll(containerRepository.findByOwnerId(userId));
            for (ContainerShare s : containerShareRepository.findBySharedToUserId(userId)) {
                if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(Instant.now())) continue;
                containerRepository.findById(s.getContainerId()).ifPresent(c -> {
                    if (!result.contains(c)) result.add(c);
                });
            }
        }
        enrichContainers(result);
        return result;
    }

    /** 批量填充 ownerName 与 sharedWith（platform-refinements #1/#2） */
    private void enrichContainers(List<Container> containers) {
        if (containers.isEmpty()) return;
        Set<Long> ownerIds = new HashSet<>();
        Set<Long> containerIds = new HashSet<>();
        for (Container c : containers) {
            ownerIds.add(c.getOwnerId());
            containerIds.add(c.getId());
        }
        Map<Long, String> ownerNameMap = new HashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            ownerNameMap.put(u.getId(), u.getRealName());
        }
        // platform-refinements #7：批量查询实例在线状态，离线时容器展示"物理实例不在线"
        Map<Long, Boolean> instanceOnlineMap = new HashMap<>();
        Set<Long> instIds = new HashSet<>();
        for (Container c : containers) instIds.add(c.getInstanceId());
        for (PhysicalInstance pi : instanceRepository.findAllById(instIds)) {
            instanceOnlineMap.put(pi.getId(), pi.isOnline());
        }
        List<ContainerShare> shares = containerShareRepository.findByContainerIdIn(containerIds);
        Set<Long> shareUserIds = shares.stream()
                .map(ContainerShare::getSharedToUserId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, User> shareUserMap = shareUserIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(shareUserIds).stream()
                        .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        Map<Long, List<ContainerShare>> sharesByContainer = shares.stream()
                .collect(java.util.stream.Collectors.groupingBy(ContainerShare::getContainerId));
        Instant now = Instant.now();
        for (Container c : containers) {
            c.setOwnerName(ownerNameMap.getOrDefault(c.getOwnerId(), "-"));
            c.setInstanceOnline(instanceOnlineMap.getOrDefault(c.getInstanceId(), false));
            List<ContainerShare> cs = sharesByContainer.getOrDefault(c.getId(), List.of());
            List<Container.ShareView> views = new ArrayList<>();
            for (ContainerShare s : cs) {
                User u = shareUserMap.get(s.getSharedToUserId());
                boolean expired = s.getExpiresAt() != null && s.getExpiresAt().isBefore(now);
                views.add(new Container.ShareView(
                        s.getId(), s.getSharedToUserId(),
                        u != null ? u.getRealName() : "-",
                        u != null ? u.getStudentId() : null,
                        s.getExpiresAt(), expired));
            }
            c.setSharedWith(views);
        }
    }

    /** 共享容器（platform-refinements #2：按工号精准共享，可限时） */
    @Audited(action = "CONTAINER_SHARE", targetType = "CONTAINER", targetIdExpr = "#containerId")
    @Transactional
    public void shareContainer(Long containerId, String targetWorkerId, Instant expiresAt) {
        Container container = getContainer(containerId);
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN
                && !container.getOwnerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅所有者可共享");
        }
        User target = userRepository.findByStudentId(targetWorkerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "无此用户（工号不匹配）"));
        if (!containerShareRepository.existsByContainerIdAndSharedToUserId(containerId, target.getId())) {
            containerShareRepository.save(ContainerShare.builder()
                    .containerId(containerId)
                    .sharedToUserId(target.getId())
                    .sharedBy(SecurityUtils.getCurrentUserId())
                    .expiresAt(expiresAt)
                    .build());
        }
    }

    /** 取消共享（platform-refinements #2） */
    @Audited(action = "CONTAINER_UNSHARE", targetType = "CONTAINER", targetIdExpr = "#containerId")
    @Transactional
    public void unshareContainer(Long containerId, Long shareId) {
        Container container = getContainer(containerId);
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN
                && !container.getOwnerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅所有者可取消共享");
        }
        containerShareRepository.findById(shareId).ifPresent(containerShareRepository::delete);
    }

    /** 同机其他用户聚合统计（任务 10.9 spec: 另有 N 用户 M 容器；platform-refinements #4：管理员可见其他用户详情） */
    public Map<String, Object> getInstanceAggregation(Long instanceId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<Container> all = containerRepository.findByInstanceId(instanceId);
        Map<Long, List<Container>> byOwner = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(Container::getOwnerId));
        List<Map<String, Object>> users = new ArrayList<>();
        long myContainers = 0, otherUsers = 0, otherContainers = 0;
        for (var e : byOwner.entrySet()) {
            Long oid = e.getKey();
            List<Container> cs = e.getValue();
            User u = userRepository.findById(oid).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", oid);
            m.put("realName", u != null ? u.getRealName() : "-");
            m.put("studentId", u != null ? u.getStudentId() : null);
            m.put("containerCount", cs.size());
            m.put("containers", cs.stream().map(c -> {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.getName());
                cm.put("status", c.getStatus());
                return cm;
            }).toList());
            users.add(m);
            if (oid.equals(userId)) myContainers = cs.size();
            else { otherUsers++; otherContainers += cs.size(); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("myContainers", myContainers);
        result.put("otherUsers", otherUsers);
        result.put("otherContainers", otherContainers);
        // platform-refinements #4：管理员可见其他正在使用的用户详情
        if (SecurityUtils.getCurrentRole() == UserRole.ADMIN) {
            result.put("users", users);
        }
        return result;
    }

    public Container getContainer(Long id) {
        return containerRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTAINER_NOT_FOUND));
    }

    /** 获取容器创建时的原始表单配置（任务 4） */
    public String getFormSnapshot(Long id) {
        Container container = getContainer(id);
        return container.getFormSnapshot();
    }

    private void validateStoragePoolAccess(Long poolId, Long userId) {
        StoragePool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_POOL_NOT_FOUND));
        if (!pool.getOwnerId().equals(userId)
                && !shareRepository.existsByPoolIdAndSharedToUserId(poolId, userId)
                && SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "无权使用此存储池");
        }
    }

    private Map<String, Object> buildDockerRunPayload(CreateContainerRequest req,
                                                       List<PortAllocation> allocations,
                                                       PhysicalInstance instance) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("imageRef", req.getImageRef());

        // GPU 透传显式化（受控端已硬编码 --gpus all 即 DeviceRequests{Count:-1}，
        // 此处显式携带 gpus 字段以便审计与生成命令可见性，不改变运行行为）
        payload.put("gpus", "all");

        // 端口映射
        List<Map<String, Object>> portMappings = new ArrayList<>();
        for (PortAllocation a : allocations) {
            portMappings.add(Map.of("containerPort", a.getContainerPort(), "hostPort", a.getHostPort()));
        }
        payload.put("portMappings", portMappings);

        // 资源限制
        payload.put("cpuLimit", req.getCpuLimit());
        payload.put("memoryLimit", req.getMemoryLimit());
        payload.put("shmSize", req.getShmSize());

        // GPU 显存软限制 env（任务 10.4 spec: PYTORCH_CUDA_ALLOC_CONF 等）
        List<String> env = new ArrayList<>();
        if (req.getEnv() != null) env.addAll(req.getEnv());
        if (req.getGpuMemoryLimit() != null) {
            env.add("PYTORCH_CUDA_ALLOC_CONF=max_split_size_mb:" + req.getGpuMemoryLimit());
            env.add("TF_FORCE_GPU_ALLOW_GROWTH=true");
        }
        payload.put("env", env);

        // SSH 密码
        payload.put("sshPassword", req.getSshPassword());

        // 存储池挂载
        if (req.getStoragePoolId() != null) {
            poolRepository.findById(req.getStoragePoolId()).ifPresent(pool -> {
                payload.put("mountPath", pool.getPoolPath());
            });
        }

        return payload;
    }

    private String serializePortMappings(List<PortAllocation> allocations) {
        try {
            List<Map<String, Integer>> mappings = allocations.stream()
                    .map(a -> Map.of("containerPort", a.getContainerPort(), "hostPort", a.getHostPort()))
                    .toList();
            return objectMapper.writeValueAsString(mappings);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 查询受控端 docker 当前实际占用的宿主端口（platform-refinements #6）。
     * 失败时返回空集（仅按 DB 记录分配，保持原有行为）。
     */
    private Set<Integer> queryDockerUsedPorts(String instanceNumber) {
        try {
            AgentCommandResult result = agentCommandService.sendCommand(
                    instanceNumber, "port.query_used", Map.of(), 15000);
            if (result == null || !result.isSuccess() || result.getOutput() == null) {
                return Set.of();
            }
            var node = objectMapper.readTree(result.getOutput());
            var ports = node.path("ports");
            Set<Integer> used = new HashSet<>();
            if (ports.isArray()) {
                for (var p : ports) {
                    int v = p.asInt(0);
                    if (v > 0) used.add(v);
                }
            }
            return used;
        } catch (Exception e) {
            log.warn("[Container] 查询占用端口失败，仅按 DB 分配: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 修改容器备注（platform-refinements #1） */
    @Transactional
    public void updateRemark(Long containerId, String remark) {
        Container container = getContainer(containerId);
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN
                && !container.getOwnerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅所有者可修改");
        }
        container.setRemark(remark);
        containerRepository.save(container);
    }

    // ===== 在线容器终端（platform-refinements #2：交互式 docker exec，前端轮询 read/write）=====

    public String openTerminal(Long containerId, String shell) {
        if (shell == null || shell.isBlank()) shell = "bash";
        Map<String, Object> payload = new HashMap<>();
        payload.put("shell", shell);
        return dispatchTerminal(containerId, "terminal.open", payload);
    }

    public void writeTerminal(Long containerId, String sessionId, String data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("data", data);
        dispatchTerminal(containerId, "terminal.write", payload);
    }

    public String readTerminal(Long containerId, String sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        return dispatchTerminal(containerId, "terminal.read", payload);
    }

    public void closeTerminal(Long containerId, String sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        dispatchTerminal(containerId, "terminal.close", payload);
    }

    private String dispatchTerminal(Long containerId, String type, Map<String, Object> payload) {
        Container container = getContainer(containerId);
        PhysicalInstance instance = requireInstanceOnline(container);
        if ("terminal.open".equals(type)) {
            payload.put("containerId", container.getDockerId());
        }
        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), type, payload, 30000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                    result != null ? result.getError() : "受控端无响应");
        }
        return result.getOutput();
    }

    /** 查看容器日志（platform-refinements #2）：tail 最近 N 条，受控端 docker logs */
    public String getContainerLogs(Long containerId, int tail) {
        Container container = getContainer(containerId);
        PhysicalInstance instance = requireInstanceOnline(container);
        Map<String, Object> payload = new HashMap<>();
        payload.put("containerId", container.getDockerId());
        payload.put("tail", String.valueOf(tail));
        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "container.logs", payload, 20000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.CONTAINER_COMMAND_FAILED,
                    result != null ? result.getError() : "受控端无响应");
        }
        try {
            return objectMapper.readTree(result.getOutput()).path("logs").asText("");
        } catch (Exception e) {
            return result.getOutput();
        }
    }

    /** 校验容器所在物理实例在线（platform-refinements：离线时所有容器操作不可进行） */
    private PhysicalInstance requireInstanceOnline(Container container) {
        PhysicalInstance instance = instanceRepository.findById(container.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
        if (!instance.isOnline() || !agentCommandService.isAgentConnected(instance.getInstanceNumber())) {
            throw new BusinessException(ErrorCode.INSTANCE_OFFLINE, "物理实例不在线，操作不可用");
        }
        return instance;
    }

    private String parseOutputField(String output, String field) {
        // 简化：受控端返回 JSON，解析指定字段
        if (output == null) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(output, Map.class);
            Object val = map.get(field);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return output.trim();
        }
    }
}
