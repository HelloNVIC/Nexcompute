package com.nexcompute.management.repository;

import com.nexcompute.management.domain.PortAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortAllocationRepository extends JpaRepository<PortAllocation, Long> {

    List<PortAllocation> findByInstanceId(Long instanceId);

    List<PortAllocation> findByContainerId(Long containerId);

    Optional<PortAllocation> findByInstanceIdAndHostPort(Long instanceId, Integer hostPort);

    void deleteByContainerId(Long containerId);
}
