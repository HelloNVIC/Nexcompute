package com.nexcompute.management.service;

import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 心跳超时检测调度任务（任务 4.4）
 * 定时扫描超过阈值未收到心跳的实例，标记为离线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatTimeoutScheduler {

    private final PhysicalInstanceRepository instanceRepository;
    private final NexcomputeProperties properties;

    @Scheduled(fixedDelayString = "${nexcompute.monitoring.offline-scan-interval-ms:10000}")
    @Transactional
    public void scanOfflineInstances() {
        int timeoutSeconds = properties.getMonitoring().getHeartbeatTimeoutSeconds();
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);

        List<PhysicalInstance> online = instanceRepository.findByStatus("ONLINE");
        int marked = 0;
        for (PhysicalInstance instance : online) {
            if (instance.getLastHeartbeat() == null || instance.getLastHeartbeat().isBefore(threshold)) {
                instance.setStatus("OFFLINE");
                instanceRepository.save(instance);
                marked++;
                log.info("[HeartbeatTimeout] 实例 {} 标记为离线（最后心跳: {}）",
                        instance.getInstanceNumber(), instance.getLastHeartbeat());
            }
        }
        if (marked > 0) {
            log.info("[HeartbeatTimeout] 本次扫描标记 {} 台实例离线", marked);
        }
    }
}
