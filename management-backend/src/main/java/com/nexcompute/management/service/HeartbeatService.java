package com.nexcompute.management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.AgentCredential;
import com.nexcompute.management.domain.Container;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.dto.HeartbeatRequest;
import com.nexcompute.management.repository.AgentCredentialRepository;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.nexcompute.management.agent.OtaProgressTracker;
import com.nexcompute.management.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 心跳服务（任务 4.2、6.1）
 * 接收受控端心跳，解析状态数据，更新实例状态。
 * 首次心跳自动注册并生成唯一物理机编号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final PhysicalInstanceRepository instanceRepository;
    private final AgentCredentialRepository credentialRepository;
    private final NexcomputeProperties properties;
    private final ObjectMapper objectMapper;
    private final MonitoringService monitoringService;
    private final ContainerRepository containerRepository;
    private final SseService sseService;
    private final OtaProgressTracker otaProgressTracker;

    @Transactional
    public PhysicalInstance processHeartbeat(HeartbeatRequest request) {
        PhysicalInstance instance;

        // Go 零值（instanceId=0, instanceNumber=""）视为未注册，走自动注册流程
        boolean hasInstanceId = request.getInstanceId() != null && request.getInstanceId() > 0;
        boolean hasInstanceNumber = request.getInstanceNumber() != null
                && !request.getInstanceNumber().isBlank();

        if (hasInstanceId) {
            // 已注册实例：校验凭证
            instance = instanceRepository.findById(request.getInstanceId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
            validateToken(instance, request.getAgentToken());
        } else if (hasInstanceNumber) {
            // 按编号查找（已注册）
            instance = instanceRepository.findByInstanceNumber(request.getInstanceNumber())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));
            validateToken(instance, request.getAgentToken());
        } else {
            // 首次心跳：自动注册（任务 6.1）
            instance = autoRegister(request);
        }

        // 更新状态
        instance.setStatus("ONLINE");
        instance.setLastHeartbeat(Instant.now());
        instance.setLastStatus(serializeStatus(request.getStatus()));

        // platform-audit-logging-ux 9.3：实例状态变动经 SSE 实时推送前端刷新列表
        try {
            Map<String, Object> ev = new HashMap<>();
            ev.put("instanceId", instance.getId());
            ev.put("instanceNumber", instance.getInstanceNumber());
            ev.put("status", instance.getStatus());
            sseService.broadcast("instance", ev);
        } catch (Exception e) {
            log.debug("[Heartbeat] instance SSE 推送失败: {}", e.getMessage());
        }

        if (request.getMachineName() != null) instance.setMachineName(request.getMachineName());
        // IP 优先取结构化 ipAddresses（任务 4.2），回退旧 ipAddress 字段
        if (request.getIpAddresses() != null && !request.getIpAddresses().isEmpty()) {
            instance.setIpAddress(request.getIpAddresses().get(0));
        } else if (request.getIpAddress() != null) {
            instance.setIpAddress(request.getIpAddress());
        }
        if (request.getOsInfo() != null) instance.setOsInfo(request.getOsInfo());
        if (request.getGpuInfo() != null) instance.setGpuInfo(request.getGpuInfo());
        // platform-refinements #2：从 status.gpuInfo 提取 GPU 名称/显存写入实例字段（管理视图展示用）
        extractGpuInfo(request.getStatus(), instance);
        if (request.getAgentVersion() != null) instance.setAgentVersion(request.getAgentVersion());
        if (request.getStorageRoot() != null) instance.setStorageRoot(request.getStorageRoot());
        // D14：同步 SMBIOS UUID（已注册实例补充主指纹）
        String normUuid = normalizeSmbiosUUID(request.getSmbiosUUID());
        if (normUuid != null) instance.setSmbiosUuid(normUuid);

        // 连接模式声明（任务 4.5）
        if (request.getConnectMode() != null) {
            String mode = request.getConnectMode();
            if ("tunnel".equalsIgnoreCase(mode)) {
                log.info("[Heartbeat] 实例 {} 声明穿透模式（敬请期待）", instance.getInstanceNumber());
            }
            instance.setConnectMode(mode.toLowerCase());
        }

        instance = instanceRepository.save(instance);

        // D2 ⑤：OTA 升级等待版本确认段推断 -- 首次心跳起算，agentVersion==目标即满+SUCCESS
        if (request.getAgentVersion() != null && instance.getInstanceNumber() != null) {
            try {
                otaProgressTracker.onHeartbeat(instance.getInstanceNumber(), request.getAgentVersion());
            } catch (Exception e) {
                log.debug("[Heartbeat] OTA 进度更新失败: {}", e.getMessage());
            }
        }

        // 持久化监控快照（任务 11.1）+ SSE 推送（任务 11.4）
        persistMonitoringSnapshot(instance, instance.getLastStatus());

        // platform-improvements 任务 4.3：按心跳回传的容器状态更新 container.status 并 SSE 推送
        updateContainerStatuses(instance, request.getContainers());

        return instance;
    }

    /**
     * 按心跳回传的容器状态更新 container.status（任务 4.3）。
     * 状态变更经既有 container SSE 事件推送前端。受控端外停止（docker stop）也会被感知。
     */
    private void updateContainerStatuses(PhysicalInstance instance,
                                         List<HeartbeatRequest.ContainerState> reported) {
        if (reported == null || reported.isEmpty()) return;
        List<Container> instanceContainers = containerRepository.findByInstanceId(instance.getId());
        if (instanceContainers.isEmpty()) return;

        for (HeartbeatRequest.ContainerState cs : reported) {
            if (cs.getId() == null) continue;
            String newStatus = mapDockerState(cs.getState());
            // 按 dockerId 匹配（兼容短/长 ID 前缀）
            Container matched = null;
            for (Container c : instanceContainers) {
                if (c.getDockerId() == null) continue;
                if (c.getDockerId().equals(cs.getId())
                        || c.getDockerId().startsWith(cs.getId())
                        || cs.getId().startsWith(c.getDockerId())) {
                    matched = c;
                    break;
                }
            }
            if (matched == null) continue;
            if (!newStatus.equals(matched.getStatus())) {
                matched.setStatus(newStatus);
                containerRepository.save(matched);
                // 经既有 container SSE 事件推送前端
                Map<String, Object> data = new HashMap<>();
                data.put("containerId", matched.getId());
                data.put("status", newStatus);
                data.put("name", matched.getName());
                data.put("instanceNumber", instance.getInstanceNumber());
                sseService.pushToUser(matched.getOwnerId(), "container", data);
            }
        }
    }

    /** Docker 原生状态 -> 容器状态枚举（CREATED/RUNNING/STOPPED/EXITED） */
    private String mapDockerState(String dockerState) {
        if (dockerState == null) return "STOPPED";
        return switch (dockerState) {
            case "running", "restarting" -> "RUNNING";
            case "created" -> "CREATED";
            case "exited", "paused", "dead" -> "STOPPED";
            default -> "STOPPED";
        };
    }
    private void persistMonitoringSnapshot(PhysicalInstance instance, String statusJson) {
        if (statusJson == null) return;
        try {
            monitoringService.persistSnapshot(instance, statusJson);
        } catch (Exception e) {
            log.warn("[Heartbeat] 持久化监控快照失败: {}", e.getMessage());
        }
    }

    /**
     * 首次心跳自动注册（任务 6.1）：自动生成唯一编号
     * platform-env-ota-realtime D14：去重匹配优先 SMBIOS UUID，空/全 0 回退 MachineGuid 再回退 MAC。
     */
    private PhysicalInstance autoRegister(HeartbeatRequest request) {
        String mac = request.getMac();
        String machineCode = request.getMachineCode();
        String smbiosUUID = normalizeSmbiosUUID(request.getSmbiosUUID());

        // 1. 优先按 SMBIOS UUID 去重（换网卡但主板不变时复用）
        if (smbiosUUID != null) {
            var existing = instanceRepository.findBySmbiosUuid(smbiosUUID);
            if (existing.isPresent()) {
                return reuseInstance(existing.get(), request, smbiosUUID, "uuid=" + smbiosUUID);
            }
        }
        // 2. 回退：机器码（MachineGuid）单独命中即复用（instance-identity：不再要求 MAC 同时
        //    匹配——MAC 采集失败时不再整体放弃匹配，修复同机重复注册）
        if (machineCode != null && !machineCode.isBlank()) {
            var existing = instanceRepository.findByMachineCode(machineCode);
            if (existing.isPresent()) {
                return reuseInstance(existing.get(), request, smbiosUUID, "machineCode=" + machineCode);
            }
        }

        String number = generateUniqueNumber();
        PhysicalInstance instance = PhysicalInstance.builder()
                .instanceNumber(number)
                .machineName(request.getMachineName())
                .ipAddress(request.getIpAddress())
                .osInfo(request.getOsInfo())
                .gpuInfo(request.getGpuInfo())
                .agentVersion(request.getAgentVersion())
                .connectMode(request.getConnectMode() != null ? request.getConnectMode().toLowerCase() : "direct")
                .status("ONLINE")
                .storageRoot(request.getStorageRoot())
                .mac(mac)
                .machineCode(machineCode)
                .smbiosUuid(smbiosUUID)
                .build();
        instance = instanceRepository.save(instance);

        // 分配鉴权凭证
        AgentCredential credential = AgentCredential.builder()
                .instanceId(instance.getId())
                .token(UUID.randomUUID().toString().replace("-", ""))
                .revoked(false)
                .build();
        credentialRepository.save(credential);

        log.info("[Heartbeat] 新受控端已注册: number={} id={} token={} mac={} uuid={}",
                instance.getInstanceNumber(), instance.getId(), credential.getToken(), mac, smbiosUUID);
        return instance;
    }

    /** 复用既有实例（按指纹命中），更新在线状态与指纹，复用凭证（无则补建） */
    private PhysicalInstance reuseInstance(PhysicalInstance inst, HeartbeatRequest request,
                                           String smbiosUUID, String matchKey) {
        inst.setStatus("ONLINE");
        inst.setLastHeartbeat(Instant.now());
        inst.setLastStatus(serializeStatus(request.getStatus()));
        if (request.getMachineName() != null) inst.setMachineName(request.getMachineName());
        if (request.getOsInfo() != null) inst.setOsInfo(request.getOsInfo());
        if (smbiosUUID != null) inst.setSmbiosUuid(smbiosUUID);
        if (request.getMac() != null) inst.setMac(request.getMac());
        if (request.getMachineCode() != null) inst.setMachineCode(request.getMachineCode());
        inst = instanceRepository.save(inst);
        // 复用既有凭证（若无则补建）
        final Long reuseInstId = inst.getId();
        credentialRepository.findByInstanceId(reuseInstId)
                .orElseGet(() -> {
                    AgentCredential c = AgentCredential.builder()
                            .instanceId(reuseInstId)
                            .token(UUID.randomUUID().toString().replace("-", ""))
                            .revoked(false).build();
                    return credentialRepository.save(c);
                });
        log.info("[Heartbeat] 已注册机器按指纹复用: number={} {}", inst.getInstanceNumber(), matchKey);
        return inst;
    }

    /** SMBIOS UUID 规范化：去空白；空或全 0 返回 null（回退降级匹配） */
    private static String normalizeSmbiosUUID(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isBlank()) return null;
        String digits = v.replace("-", "");
        if (digits.chars().allMatch(c -> c == '0')) return null; // 全 0
        return v;
    }

    /**
     * 自动生成唯一物理机编号（任务 6.1）
     * 格式：两位递增序号（01, 02, ...）
     */
    private String generateUniqueNumber() {
        long count = instanceRepository.count();
        for (int i = (int) count + 1; i < 1000; i++) {
            String number = String.format("%02d", i);
            if (!instanceRepository.existsByInstanceNumber(number)) {
                return number;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法生成唯一物理机编号");
    }

    /** 从 status.gpuInfo 提取名称/显存写入实例字段（platform-refinements #2） */
    @SuppressWarnings("unchecked")
    private void extractGpuInfo(Map<String, Object> status, PhysicalInstance instance) {
        if (status == null) return;
        Object gpu = status.get("gpuInfo");
        if (gpu instanceof Map<?, ?> g) {
            Object name = g.get("name");
            Object memTotal = g.get("memoryTotal");
            if (name != null && !name.toString().isBlank()) {
                String info = name.toString() + (memTotal != null ? " (" + memTotal + " MiB)" : "");
                instance.setGpuInfo(info);
            }
        }
    }

    private void validateToken(PhysicalInstance instance, String token) {
        AgentCredential credential = credentialRepository.findByInstanceId(instance.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "无连接凭证"));
        if (credential.getRevoked() || !credential.getToken().equals(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "受控端凭证无效");
        }
    }

    private String serializeStatus(Map<String, Object> status) {
        if (status == null) return null;
        try {
            return objectMapper.writeValueAsString(status);
        } catch (JsonProcessingException e) {
            log.warn("[Heartbeat] 序列化状态失败: {}", e.getMessage());
            return null;
        }
    }

    NexcomputeProperties.Monitoring getMonitoringConfig() {
        return properties.getMonitoring();
    }
}
