package com.nexcompute.management.repository;

import com.nexcompute.management.domain.StoragePool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StoragePoolRepository extends JpaRepository<StoragePool, Long> {

    List<StoragePool> findByOwnerId(Long ownerId);

    List<StoragePool> findByOwnerIdIn(Collection<Long> ownerIds);

    List<StoragePool> findByInstanceId(Long instanceId);

    boolean existsByInstanceIdAndOwnerIdAndProjectName(Long instanceId, Long ownerId, String projectName);
}
