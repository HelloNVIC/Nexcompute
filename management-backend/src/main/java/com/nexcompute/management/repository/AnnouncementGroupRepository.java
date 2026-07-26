package com.nexcompute.management.repository;

import com.nexcompute.management.domain.AnnouncementGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnnouncementGroupRepository extends JpaRepository<AnnouncementGroup, Long> {

    List<AnnouncementGroup> findByAnnouncementId(Long announcementId);

    List<AnnouncementGroup> findByAnnouncementIdIn(java.util.Collection<Long> announcementIds);

    void deleteByAnnouncementId(Long announcementId);

    /** 公告定向的课题组 ID 集合 */
    default List<Long> findGroupIdsByAnnouncementId(Long announcementId) {
        return findByAnnouncementId(announcementId).stream()
                .map(AnnouncementGroup::getGroupId)
                .toList();
    }
}
