package com.nexcompute.management.repository;

import com.nexcompute.management.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findByStatusOrderByPublishAtDesc(String status);

    List<Announcement> findByStatusAndPublishAtBefore(String status, Instant time);

    List<Announcement> findByStatusAndPublishAtBetween(String status, Instant start, Instant end);
}
