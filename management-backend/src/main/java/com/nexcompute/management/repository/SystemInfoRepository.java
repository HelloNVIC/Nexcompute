package com.nexcompute.management.repository;

import com.nexcompute.management.domain.SystemInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemInfoRepository extends JpaRepository<SystemInfo, Short> {

    default Optional<SystemInfo> findSingleton() {
        return findById((short) 1);
    }
}
