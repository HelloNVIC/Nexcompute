package com.nexcompute.management.repository;

import com.nexcompute.management.domain.EnvFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EnvFileRepository extends JpaRepository<EnvFile, Long> {

    Optional<EnvFile> findByFilename(String filename);
}
