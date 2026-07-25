package com.nexcompute.management.repository;

import com.nexcompute.management.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByOperatorIdOrderByCreatedAtDesc(Long operatorId, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant start, Instant end, Pageable pageable);

    Page<AuditLog> findByActionContainingOrderByCreatedAtDesc(String action, Pageable pageable);
}
