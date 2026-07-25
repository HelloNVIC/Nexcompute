package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ResourceQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourceQuotaRepository extends JpaRepository<ResourceQuota, Long> {

    Optional<ResourceQuota> findByScopeAndOwnerId(String scope, Long ownerId);
}
