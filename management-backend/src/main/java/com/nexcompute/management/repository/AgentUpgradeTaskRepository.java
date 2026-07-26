package com.nexcompute.management.repository;

import com.nexcompute.management.domain.AgentUpgradeTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentUpgradeTaskRepository extends JpaRepository<AgentUpgradeTask, Long> {

    List<AgentUpgradeTask> findByInstanceIdOrderByCreatedAtDesc(Long instanceId);

    List<AgentUpgradeTask> findAllByOrderByCreatedAtDesc();
}
