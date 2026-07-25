package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.GroupMember;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.GroupMemberRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 课题组管理服务（任务 3.2）
 * 课题组信息由管理员和导师维护。导师只能编辑自己课题组。
 */
@Service
@RequiredArgsConstructor
public class ResearchGroupService {

    private final ResearchGroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    public ResearchGroup getGroup(Long id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
    }

    /** 获取当前用户的课题组（platform-refinements #2：导师按 mentor_id，学生按 group_id） */
    public ResearchGroup getMyGroup() {
        Long userId = SecurityUtils.getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() == UserRole.MENTOR) {
            return groupRepository.findByMentorId(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND, "您还没有课题组"));
        }
        // 学生：按 group_id
        if (user.getGroupId() == null) {
            throw new BusinessException(ErrorCode.GROUP_NOT_FOUND, "您还没有课题组");
        }
        return groupRepository.findById(user.getGroupId())
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND, "您还没有课题组"));
    }

    public List<ResearchGroup> listAll() {
        return groupRepository.findAll();
    }

    @Audited(action = "GROUP_CREATE", targetType = "GROUP", targetIdExpr = "#result.id")
    @Transactional
    public ResearchGroup createGroup(String name, String description, Long mentorId) {
        ResearchGroup group = ResearchGroup.builder()
                .name(name)
                .description(description)
                .mentorId(mentorId)
                .build();
        return groupRepository.save(group);
    }

    @Audited(action = "GROUP_UPDATE", targetType = "GROUP", targetIdExpr = "#id")
    @Transactional
    public ResearchGroup updateGroup(Long id, String name, String description) {
        ResearchGroup group = getGroup(id);
        // 导师只能编辑自己的课题组
        if (SecurityUtils.getCurrentRole() == com.nexcompute.management.domain.UserRole.MENTOR) {
            if (!group.getMentorId().equals(SecurityUtils.getCurrentUserId())) {
                throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只能编辑自己的课题组");
            }
        }
        if (name != null) group.setName(name);
        if (description != null) group.setDescription(description);
        return groupRepository.save(group);
    }

    /**
     * 删除课题组（platform-improvements 任务 5.3，管理员）。
     * 删除课题组成员关系；用户的 group_id 置空（用户不再属于该组，仍可被管理员管理）。
     */
    @Audited(action = "GROUP_DELETE", targetType = "GROUP", targetIdExpr = "#id")
    @Transactional
    public void deleteGroup(Long id) {
        ResearchGroup group = getGroup(id);
        // 清理成员关系
        groupMemberRepository.findByGroupId(id).forEach(groupMemberRepository::delete);
        // 解除用户 group_id 关联（保留用户，group_id 可空已是现状）
        userRepository.findByGroupId(id).forEach(u -> {
            u.setGroupId(null);
            userRepository.save(u);
        });
        groupRepository.delete(group);
    }

    /** 获取课题组的学生列表（platform-refinements #1：仅 STUDENT 角色，不含导师） */
    public List<User> getGroupStudents(Long groupId) {
        return userRepository.findStudentsByGroupId(groupId);
    }

    /** 将学生加入课题组 */
    @Transactional
    public void addMember(Long groupId, Long userId) {
        getGroup(groupId);
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            groupMemberRepository.save(GroupMember.builder()
                    .groupId(groupId)
                    .userId(userId)
                    .build());
        }
        // 同步主 group_id
        userRepository.findById(userId).ifPresent(u -> {
            u.setGroupId(groupId);
            userRepository.save(u);
        });
    }

    /** 按工号加入课题组（platform-refinements #6） */
    @Transactional
    public void addMemberByWorkerId(Long groupId, String workerId) {
        getGroup(groupId);
        User user = userRepository.findByStudentId(workerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "无此用户（工号不匹配）"));
        addMember(groupId, user.getId());
    }

    /**
     * 从课题组移除学生（platform-refinements #6）：
     * 学生 group_id 置空（转"无课题组"），容器与存储池保留；登录时阻断。
     */
    @Transactional
    public void removeMember(Long groupId, Long userId) {
        groupMemberRepository.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .ifPresent(groupMemberRepository::delete);
        userRepository.findById(userId).ifPresent(u -> {
            if (groupId.equals(u.getGroupId())) {
                u.setGroupId(null);
                userRepository.save(u);
            }
        });
    }
}
