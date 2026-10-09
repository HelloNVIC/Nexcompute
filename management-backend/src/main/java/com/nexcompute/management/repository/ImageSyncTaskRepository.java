package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ImageSyncTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 镜像同步任务仓库（V36）
 */
public interface ImageSyncTaskRepository extends JpaRepository<ImageSyncTask, Long> {

    List<ImageSyncTask> findByBatchIdOrderByIdAsc(Long batchId);

    /** 该实例的镜像同步任务数（instance-identity：删除前占用检查） */
    long countByInstanceId(Long instanceId);

    /** 删除实例的镜像同步任务（instance-identity：删除实例级联清理，纯派生记录随实例删除） */
    void deleteByInstanceId(Long instanceId);
}
