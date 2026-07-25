package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.GroupMember;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserFieldConfig;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.CreateUserRequest;
import com.nexcompute.management.dto.UpdateUserRequest;
import com.nexcompute.management.dto.UserInfoDto;
import com.nexcompute.management.repository.GroupMemberRepository;
import com.nexcompute.management.repository.ContainerShareRepository;
import com.nexcompute.management.repository.ContainerRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.UserFieldConfigRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理服务（任务 2.6）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ResearchGroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserFieldConfigRepository fieldConfigRepository;
    private final ContainerRepository containerRepository;
    private final ContainerShareRepository containerShareRepository;
    private final PasswordEncoder passwordEncoder;

    /** 获取用户必填项配置（platform-refinements #5） */
    public UserFieldConfig getFieldConfig() {
        return fieldConfigRepository.findSingleton()
                .orElseGet(() -> UserFieldConfig.builder().id((short) 1)
                        .realName(true).studentId(false).email(false).phone(false).groupId(false).build());
    }

    /** 更新用户必填项配置（platform-refinements #5） */
    @Audited(action = "USER_FIELD_CONFIG_UPDATE", targetType = "SYSTEM", targetIdExpr = "'config'")
    @Transactional
    public UserFieldConfig updateFieldConfig(Boolean realName, Boolean studentId, Boolean email,
                                              Boolean phone, Boolean groupId) {
        UserFieldConfig cfg = fieldConfigRepository.findSingleton()
                .orElseGet(() -> UserFieldConfig.builder().id((short) 1).build());
        if (realName != null) cfg.setRealName(realName);
        if (studentId != null) cfg.setStudentId(studentId);
        if (email != null) cfg.setEmail(email);
        if (phone != null) cfg.setPhone(phone);
        if (groupId != null) cfg.setGroupId(groupId);
        return fieldConfigRepository.save(cfg);
    }

    /** 按必填项配置校验（platform-refinements #5） */
    private void validateRequiredFields(String realName, String studentId, String email,
                                        String phone, Long groupId) {
        UserFieldConfig cfg = getFieldConfig();
        if (Boolean.TRUE.equals(cfg.getRealName()) && (realName == null || realName.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "姓名为必填项");
        }
        if (Boolean.TRUE.equals(cfg.getStudentId()) && (studentId == null || studentId.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工号/学号为必填项");
        }
        if (Boolean.TRUE.equals(cfg.getEmail()) && (email == null || email.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱为必填项");
        }
        if (Boolean.TRUE.equals(cfg.getPhone()) && (phone == null || phone.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号为必填项");
        }
        if (Boolean.TRUE.equals(cfg.getGroupId()) && groupId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "课题组为必填项");
        }
    }

    @Audited(action = "USER_CREATE", targetType = "USER", targetIdExpr = "#request.username")
    @Transactional
    public UserInfoDto createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
        if (request.getRole() == UserRole.STUDENT && request.getGroupId() != null) {
            validateGroupExists(request.getGroupId());
        }
        // platform-refinements #5：按管理员配置的必填项校验
        validateRequiredFields(request.getRealName(), request.getStudentId(),
                request.getEmail(), request.getPhone(), request.getGroupId());
        User user = User.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .realName(request.getRealName())
                .role(request.getRole())
                .studentId(request.getStudentId())
                .email(request.getEmail())
                .phone(request.getPhone())
                .groupId(request.getGroupId())
                .status("ACTIVE")
                .build();
        user = userRepository.save(user);
        log.info("[User] 管理员创建用户: {} ({})", user.getUsername(), user.getRole());
        return UserInfoDto.from(user, resolveGroupName(user));
    }

    @Audited(action = "USER_UPDATE", targetType = "USER", targetIdExpr = "#id")
    @Transactional
    public UserInfoDto updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (request.getRealName() != null) user.setRealName(request.getRealName());
        if (request.getRole() != null) user.setRole(request.getRole());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        if (request.getGroupId() != null) {
            validateGroupExists(request.getGroupId());
            user.setGroupId(request.getGroupId());
        }
        if (request.getStatus() != null) user.setStatus(request.getStatus());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        user = userRepository.save(user);
        return UserInfoDto.from(user, resolveGroupName(user));
    }

    @Audited(action = "USER_DISABLE", targetType = "USER", targetIdExpr = "#id")
    @Transactional
    public void disableUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.setStatus("DISABLED");
        userRepository.save(user);
        log.info("[User] 用户已禁用: {}", user.getUsername());
    }

    /**
     * 管理用户课题组归属（platform-refinements 9.1）：加入/移除课题组。
     * 同步 group_member 关系（多对多）并将主 group_id 保持为目标集合中的一个。
     */
    @Audited(action = "USER_UPDATE_GROUPS", targetType = "USER", targetIdExpr = "#id")
    @Transactional
    public UserInfoDto updateUserGroups(Long id, List<Long> groupIds) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Set<Long> target = groupIds == null ? new HashSet<>() : new HashSet<>(groupIds);
        for (Long gid : target) validateGroupExists(gid);

        List<GroupMember> current = groupMemberRepository.findByUserId(id);
        Set<Long> currentIds = current.stream().map(GroupMember::getGroupId).collect(Collectors.toSet());
        // 移除不在目标集合中的归属
        for (GroupMember m : current) {
            if (!target.contains(m.getGroupId())) groupMemberRepository.delete(m);
        }
        // 加入新归属
        for (Long gid : target) {
            if (!currentIds.contains(gid)) {
                groupMemberRepository.save(GroupMember.builder().groupId(gid).userId(id).build());
            }
        }
        // 同步主 group_id
        if (target.isEmpty()) {
            user.setGroupId(null);
        } else if (!target.contains(user.getGroupId())) {
            user.setGroupId(target.iterator().next());
        }
        user = userRepository.save(user);
        return UserInfoDto.from(user, resolveGroupName(user));
    }

    /** 重置用户密码（platform-refinements 9.1） */
    @Audited(action = "USER_RESET_PASSWORD", targetType = "USER", targetIdExpr = "#id")
    @Transactional
    public void resetPassword(Long id, String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码不能为空");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
        log.info("[User] 管理员重置用户密码: {}", user.getUsername());
    }

    /**
     * 删除用户（platform-refinements #3）。
     * 有运行中容器则拒绝；否则清理成员关系/共享关系并删除用户。
     */
    @Audited(action = "USER_DELETE", targetType = "USER", targetIdExpr = "#id")
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可删除用户");
        }
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除当前登录用户");
        }
        // 运行中容器存在则拒绝
        if (!containerRepository.findByOwnerId(id).stream()
                .filter(c -> "RUNNING".equals(c.getStatus())).toList().isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户存在运行中容器，请先停止/删除后再删除用户");
        }
        // 清理：课题组成员关系、作为共享目标的容器共享关系
        groupMemberRepository.findByUserId(id).forEach(groupMemberRepository::delete);
        containerShareRepository.findBySharedToUserId(id).forEach(containerShareRepository::delete);
        // 用户作为导师的课题组 mentor_id 置空（保留课题组）
        groupRepository.findByMentorId(id).ifPresent(g -> {
            g.setMentorId(null);
            groupRepository.save(g);
        });
        userRepository.delete(user);
        log.info("[User] 用户已删除: {}", user.getUsername());
    }

    public Page<UserInfoDto> listUsers(UserRole role, Pageable pageable) {
        Page<User> users = (role != null)
                ? userRepository.findByRole(role, pageable)
                : userRepository.findAll(pageable);
        return users.map(u -> UserInfoDto.from(u, resolveGroupName(u)));
    }

    public UserInfoDto getUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserInfoDto.from(user, resolveGroupName(user));
    }

    /** 用户所属课题组列表（platform-refinements 9.2：编辑用户弹窗回显） */
    public List<ResearchGroup> getUserGroups(Long id) {
        userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return groupMemberRepository.findByUserId(id).stream()
                .map(gm -> groupRepository.findById(gm.getGroupId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private void validateGroupExists(Long groupId) {
        if (!groupRepository.existsById(groupId)) {
            throw new BusinessException(ErrorCode.GROUP_NOT_FOUND);
        }
    }

    private String resolveGroupName(User user) {
        if (user.getGroupId() == null) return null;
        return groupRepository.findById(user.getGroupId())
                .map(ResearchGroup::getName).orElse(null);
    }
}
