package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ContainerShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ContainerShareRepository extends JpaRepository<ContainerShare, Long> {

    List<ContainerShare> findByContainerId(Long containerId);

    List<ContainerShare> findBySharedToUserId(Long userId);

    List<ContainerShare> findByContainerIdIn(Collection<Long> containerIds);

    boolean existsByContainerIdAndSharedToUserId(Long containerId, Long userId);
}
