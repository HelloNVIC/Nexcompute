package com.nexcompute.management.repository;

import com.nexcompute.management.domain.AgentCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentCredentialRepository extends JpaRepository<AgentCredential, Long> {

    Optional<AgentCredential> findByInstanceId(Long instanceId);

    Optional<AgentCredential> findByToken(String token);

    /** 删除实例的鉴权凭证（instance-identity：删除实例级联清理） */
    void deleteByInstanceId(Long instanceId);
}
