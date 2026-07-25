package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ImageShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImageShareRepository extends JpaRepository<ImageShare, Long> {

    List<ImageShare> findBySharedToUserId(Long userId);

    boolean existsByImageIdAndSharedToUserId(Long imageId, Long userId);
}
