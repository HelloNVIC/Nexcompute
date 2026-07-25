package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 监控服务（任务 11.1、11.2、11.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final MonitoringHistoryRepository historyRepository;
    private final PhysicalInstanceRepository instanceRepository;
    private final ContainerRepository containerRepository;
    private final MachineAllocationRepository allocationRepository;
    private final AgentCommandService agentCommandService;
    private final NexcomputeProperties properties;
    private final SseService sseService;
    private final ObjectMapper objectMapper;

    /**
     * 持久化心跳快照（任务 11.1）
     */
    @Transactional
    public void persistSnapshot(PhysicalInstance instance, String statusJson) {
        if (statusJson == null) return;
        try {
            JsonNode status = objectMapper.readTree(statusJson);

            MonitoringHistory history = MonitoringHistory.builder()
                    .instanceId(instance.getId())
                    .instanceNumber(instance.getInstanceNumber())
                    .cpuUsage(floatVal(status, "cpuUsage"))
                    .cpuTemp(floatVal(status, "cpuTemp"))
                    .gpuUsage(floatVal(status, "gpuUsage"))
                    .gpuTemp(floatVal(status, "gpuTemp"))
                    .memoryUsage(floatVal(status, "memoryUsage"))
                    .memoryTotal(longVal(status, "memoryTotal"))
                    .memoryUsed(longVal(status, "memoryUsed"))
                    // platform-improvements 任务 4.2：结构化 GPU 显存入库（gpuInfo.memoryTotal/Used，MiB）
                    .gpuMemoryTotal(longVal(status.path("gpuInfo"), "memoryTotal"))
                    .gpuMemoryUsed(longVal(status.path("gpuInfo"), "memoryUsed"))
                    .statusSnapshot(statusJson)
                    .build();
            historyRepository.save(history);

            // 通过 SSE 推送给相关在线用户（任务 11.4）
            pushMonitoringToRelevantUsers(instance, status);
        } catch (Exception e) {
            log.warn("[Monitoring] 解析状态失败: {}", e.getMessage());
        }
    }

    /**
     * 历史趋势查询（任务 11.8）
     */
    public List<MonitoringHistory> getHistory(Long instanceId, Instant start, Instant end) {
        if (start == null) start = Instant.now().minus(30, ChronoUnit.DAYS);
        if (end == null) end = Instant.now();
        return historyRepository.findByInstanceIdAndRecordedAtBetweenOrderByRecordedAtDesc(instanceId, start, end);
    }

    public List<MonitoringHistory> getRecentHistory(Long instanceId) {
        return historyRepository.findTop100ByInstanceIdOrderByRecordedAtDesc(instanceId);
    }

    /**
     * 进程列表查询（任务 11.5）
     * 管理员=宿主机全量、学生=自己容器、导师=学生容器
     */
    public String getProcessList(Long instanceId) {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();
        PhysicalInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND));

        Map<String, Object> payload = new HashMap<>();
        if (role == UserRole.ADMIN) {
            payload.put("scope", "host");
        } else if (role == UserRole.MENTOR) {
            // 导师：课题组学生在此实例上的容器
            List<Container> containers = containerRepository.findByInstanceId(instanceId);
            List<String> dockerIds = containers.stream()
                    .map(Container::getDockerId)
                    .filter(Objects::nonNull)
                    .toList();
            payload.put("scope", "containers");
            payload.put("containerIds", dockerIds);
        } else {
            // 学生：自己的容器
            List<Container> containers = containerRepository.findByInstanceId(instanceId).stream()
                    .filter(c -> c.getOwnerId().equals(userId))
                    .toList();
            List<String> dockerIds = containers.stream()
                    .map(Container::getDockerId)
                    .filter(Objects::nonNull)
                    .toList();
            payload.put("scope", "containers");
            payload.put("containerIds", dockerIds);
        }

        AgentCommandResult result = agentCommandService.sendCommand(
                instance.getInstanceNumber(), "process.list", payload, 90000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED,
                    result != null ? result.getError() : "受控端无响应");
        }
        return result.getOutput();
    }

    /**
     * 可配置采集粒度（任务 11.2）
     */
    public int getCollectIntervalSeconds() {
        return properties.getMonitoring().getCollectIntervalSeconds();
    }

    private void pushMonitoringToRelevantUsers(PhysicalInstance instance, JsonNode status) {
        // 推送给分配到此实例的用户
        List<MachineAllocation> allocations = allocationRepository.findByInstanceId(instance.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("instanceNumber", instance.getInstanceNumber());
        data.put("status", status);
        data.put("timestamp", System.currentTimeMillis());

        for (MachineAllocation a : allocations) {
            sseService.pushMonitoring(a.getUserId(), data);
        }
        // 管理员也接收
        // 简化：管理员通过订阅时单独处理
    }

    private Float floatVal(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isNumber()) {
            return (float) node.get(field).asDouble();
        }
        return null;
    }

    private Long longVal(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isNumber()) {
            return node.get(field).asLong();
        }
        return null;
    }
}
