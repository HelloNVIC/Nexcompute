package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ImageSyncTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 镜像同步任务仓库（V36）
 */
public interface ImageSyncTaskRepository extends JpaRepository<ImageSyncTask, Long> {

    List<ImageSyncTask> findByBatchIdOrderByIdAsc(Long batchId);
}
