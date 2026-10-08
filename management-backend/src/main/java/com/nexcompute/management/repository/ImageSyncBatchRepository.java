package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ImageSyncBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 镜像同步批次仓库（V36）
 */
public interface ImageSyncBatchRepository extends JpaRepository<ImageSyncBatch, Long> {

    /** 查某镜像当前进行中的批次（每镜像同时至多一个 RUNNING，部分唯一索引约束） */
    Optional<ImageSyncBatch> findFirstByImageIdAndStatusOrderByIdDesc(Long imageId, String status);
}
