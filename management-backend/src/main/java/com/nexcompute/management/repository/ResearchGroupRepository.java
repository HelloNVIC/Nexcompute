package com.nexcompute.management.repository;

import com.nexcompute.management.domain.ResearchGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResearchGroupRepository extends JpaRepository<ResearchGroup, Long> {

    Optional<ResearchGroup> findByMentorId(Long mentorId);
}
