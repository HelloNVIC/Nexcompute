package com.nexcompute.management.service;

import com.nexcompute.management.domain.PortAllocation;
import com.nexcompute.management.repository.PortAllocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 端口分配服务（任务 10.2）
 * per-machine 分配表，防止单机端口冲突。
 * 端口范围：30000-32767（Docker 常用动态端口范围）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortAllocationService {

    private static final int PORT_RANGE_START = 30000;
    private static final int PORT_RANGE_END = 32767;

    private final PortAllocationRepository allocationRepository;

    @Transactional
    public PortAllocation allocatePort(Long instanceId, int containerPort) {
        return allocatePort(instanceId, containerPort, java.util.Collections.emptySet());
    }

    /**
     * 分配端口（platform-refinements #6）。
     * 同时避开 DB 已记录占用 与 docker 实际占用（含系统外容器/泄漏端口）。
     */
    @Transactional
    public PortAllocation allocatePort(Long instanceId, int containerPort, java.util.Set<Integer> dockerUsedPorts) {
        Set<Integer> usedPorts = new HashSet<>();
        allocationRepository.findByInstanceId(instanceId).forEach(a -> usedPorts.add(a.getHostPort()));
        if (dockerUsedPorts != null) usedPorts.addAll(dockerUsedPorts);

        for (int port = PORT_RANGE_START; port <= PORT_RANGE_END; port++) {
            if (!usedPorts.contains(port)) {
                PortAllocation allocation = PortAllocation.builder()
                        .instanceId(instanceId)
                        .containerPort(containerPort)
                        .hostPort(port)
                        .build();
                log.info("[PortAlloc] 分配端口: instance={} containerPort={} -> hostPort={} (docker占用 {} 个)",
                        instanceId, containerPort, port, dockerUsedPorts == null ? 0 : dockerUsedPorts.size());
                return allocationRepository.save(allocation);
            }
        }
        throw new RuntimeException("无可用端口");
    }

    @Transactional
    public void releaseByContainer(Long containerId) {
        allocationRepository.deleteByContainerId(containerId);
        log.info("[PortAlloc] 释放容器端口: container={}", containerId);
    }

    /** 按 allocation id 释放（platform-refinements #6：创建失败时按 id 回收，原 releaseByContainer(null) 是空操作导致泄漏） */
    @Transactional
    public void releaseByIds(List<Long> allocationIds) {
        if (allocationIds == null || allocationIds.isEmpty()) return;
        allocationRepository.deleteAllById(allocationIds);
        log.info("[PortAlloc] 按 id 释放端口: ids={}", allocationIds);
    }

    public List<PortAllocation> getByContainer(Long containerId) {
        return allocationRepository.findByContainerId(containerId);
    }

    /** 绑定容器 ID（创建容器后关联） */
    @Transactional
    public void bindContainer(List<Long> allocationIds, Long containerId) {
        allocationIds.forEach(id -> allocationRepository.findById(id).ifPresent(a -> {
            a.setContainerId(containerId);
            allocationRepository.save(a);
        }));
    }
}
