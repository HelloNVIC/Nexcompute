package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ImageMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImageMetadataRepository extends JpaRepository<ImageMetadata, Long> {

    List<ImageMetadata> findByOwnerId(Long ownerId);

    List<ImageMetadata> findByGroupId(Long groupId);

    List<ImageMetadata> findByIsPublicTrue();

    List<ImageMetadata> findByVisibility(String visibility);

    Optional<ImageMetadata> findByNameAndTagAndOwnerId(String name, String tag, Long ownerId);

    boolean existsByNameAndTagAndOwnerId(String name, String tag, Long ownerId);

    /** registry-image-distribution：重名校验按全局 name:tag（不限 owner，避免 resolveVisibleImage 引用歧义）。 */
    boolean existsByNameAndTag(String name, String tag);
}
