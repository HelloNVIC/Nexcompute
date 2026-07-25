package com.nexcompute.management.repository;

import com.nexcompute.management.domain.MonitoringHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MonitoringHistoryRepository extends JpaRepository<MonitoringHistory, Long> {

    List<MonitoringHistory> findByInstanceIdAndRecordedAtBetweenOrderByRecordedAtDesc(
            Long instanceId, Instant start, Instant end);

    List<MonitoringHistory> findTop100ByInstanceIdOrderByRecordedAtDesc(Long instanceId);

    @Modifying
    @Query("DELETE FROM MonitoringHistory m WHERE m.recordedAt < :before")
    int deleteOlderThan(Instant before);
}
