package com.nexcompute.management.service;

import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.repository.MonitoringHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 监控历史数据清理调度任务（任务 11.3）
 * 保留近 30 日，超过的自动清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringDataCleanupScheduler {

    private final MonitoringHistoryRepository historyRepository;
    private final NexcomputeProperties properties;

    @Scheduled(cron = "0 0 3 * * *") // 每天 3:00 执行
    @Transactional
    public void cleanup() {
        int retentionDays = properties.getMonitoring().getRetentionDays();
        Instant threshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = historyRepository.deleteOlderThan(threshold);
        log.info("[MonitoringCleanup] 清理 {} 日前数据: 删除 {} 条", retentionDays, deleted);
    }
}
