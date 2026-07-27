package com.nexcompute.management.audit;

import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 解析操作时导师归属快照 mentorIdAtOp（D5）。
 * 学生：取其当时 groupId 对应 research_group.mentor_id；孤儿学生返回 null。
 * 导师/管理员：返回 null（自己即操作人，导师查询走 operatorId=me）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentorSnapshotService {

    private final ResearchGroupRepository researchGroupRepository;

    public Long resolveMentorIdAtOp() {
        try {
            UserPrincipal user = SecurityUtils.getCurrentUser();
            if (user.getRole() != UserRole.STUDENT) {
                return null;
            }
            Long groupId = user.getGroupId();
            if (groupId == null) {
                return null;
            }
            return researchGroupRepository.findById(groupId)
                    .map(ResearchGroup::getMentorId)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[Audit] 解析 mentorIdAtOp 失败：{}", e.getMessage());
            return null;
        }
    }
}
