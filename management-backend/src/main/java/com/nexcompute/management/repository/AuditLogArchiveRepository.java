package com.nexcompute.management.repository;

import com.nexcompute.management.domain.AuditLogArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 审计归档表 repository（镜像热表，导师跨表历史查询按 mentorIdAtOp 快照过滤）。
 */
@Repository
public interface AuditLogArchiveRepository extends JpaRepository<AuditLogArchive, Long>, JpaSpecificationExecutor<AuditLogArchive> {

    Page<AuditLogArchive> findByOperatorIdOrderByCreatedAtDesc(Long operatorId, Pageable pageable);

    Page<AuditLogArchive> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant start, Instant end, Pageable pageable);

    Page<AuditLogArchive> findByOperationNo(String operationNo, Pageable pageable);
}
