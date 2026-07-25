package com.nexcompute.management.repository;

import com.nexcompute.management.domain.StoragePoolShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StoragePoolShareRepository extends JpaRepository<StoragePoolShare, Long> {

    List<StoragePoolShare> findByPoolId(Long poolId);

    List<StoragePoolShare> findBySharedToUserId(Long userId);

    boolean existsByPoolIdAndSharedToUserId(Long poolId, Long userId);

    /** 删除某存储池的所有共享关系（platform-refinements 7.2：删除存储池时清理） */
    void deleteByPoolId(Long poolId);
}
