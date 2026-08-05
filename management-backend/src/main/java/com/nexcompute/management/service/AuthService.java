package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.dto.*;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务（任务 2.2、3.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ResearchGroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final RegistrationLinkRepository registrationLinkRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "账号不存在"));

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "密码错误");
        }

        // platform-refinements #6：学生未分配课题组则阻断登录（容器/存储池保留，但无法使用系统）
        if (user.getRole() == UserRole.STUDENT && user.getGroupId() == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "暂未分配课题组，无法使用本系统");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        String groupName = resolveGroupName(user);

        log.info("[Auth] 用户登录成功: {} ({})", user.getUsername(), user.getRole());
        return LoginResponse.builder()
                .token(token)
                .user(UserInfoDto.from(user, groupName))
                .build();
    }

    /**
     * 学生通过注册链接注册（任务 3.5）
     */
    @Transactional
    public UserInfoDto register(RegisterRequest request) {
        RegistrationLink link = registrationLinkRepository.findByToken(request.getToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID));

        if (!"STUDENT".equals(link.getLinkType())) {
            throw new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID, "该链接非学生注册链接");
        }
        if (!link.isValid()) {
            ErrorCode code = link.isExpired() ? ErrorCode.REGISTRATION_LINK_EXPIRED
                    : ErrorCode.REGISTRATION_LINK_INVALID;
            throw new BusinessException(code);
        }

        if (userRepository.existsByUsername(request.getStudentId())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "工号/学号已被注册");
        }

        // 扣减次数
        link.setRemainingCount(link.getRemainingCount() - 1);
        if (link.getRemainingCount() <= 0) {
            link.setStatus("EXHAUSTED");
        }
        registrationLinkRepository.save(link);

        User user = User.builder()
                .username(request.getStudentId())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .realName(request.getRealName())
                .role(UserRole.STUDENT)
                .studentId(request.getStudentId())
                .email(request.getEmail())
                .phone(request.getPhone())
                .groupId(link.getGroupId())
                .status("ACTIVE")
                .build();
        user = userRepository.save(user);

        // 加入课题组关系表（group_member），与 user.group_id 保持一致（修复详情显示"无课题组"）
        if (link.getGroupId() != null
                && !groupMemberRepository.existsByGroupIdAndUserId(link.getGroupId(), user.getId())) {
            groupMemberRepository.save(GroupMember.builder()
                    .groupId(link.getGroupId())
                    .userId(user.getId())
                    .build());
        }

        // 加入课题组关系表
        String groupName = resolveGroupName(user);
        // email-notification 5.1：注册成功后异步发送注册邮件（mandatory，用户不可关）
        sendRegisteredEmail(user, groupName);
        return UserInfoDto.from(user, groupName);
    }

    /**
     * 导师邀请注册（管理员创建导师邀请链接，导师凭令牌注册并填写课题组信息）。
     * 校验链接为 MENTOR 类型且有效 -> 创建课题组（mentor_id=新用户）-> 创建导师用户（groupId=新组）-> 扣减次数。
     */
    @Transactional
    public UserInfoDto registerMentor(MentorRegisterRequest request) {
        RegistrationLink link = registrationLinkRepository.findByToken(request.getToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID));

        if (!"MENTOR".equals(link.getLinkType())) {
            throw new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID, "该链接非导师邀请链接");
        }
        if (!link.isValid()) {
            ErrorCode code = link.isExpired() ? ErrorCode.REGISTRATION_LINK_EXPIRED
                    : ErrorCode.REGISTRATION_LINK_INVALID;
            throw new BusinessException(code);
        }

        if (userRepository.existsByUsername(request.getStudentId())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "工号已被注册");
        }

        // 扣减次数
        link.setRemainingCount(link.getRemainingCount() - 1);
        if (link.getRemainingCount() <= 0) {
            link.setStatus("EXHAUSTED");
        }
        registrationLinkRepository.save(link);

        // 先建导师用户（无组），再建课题组（mentor_id=用户），回填 groupId
        User user = User.builder()
                .username(request.getStudentId())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .realName(request.getRealName())
                .role(UserRole.MENTOR)
                .studentId(request.getStudentId())
                .email(request.getEmail())
                .phone(request.getPhone())
                .status("ACTIVE")
                .build();
        user = userRepository.save(user);

        ResearchGroup group = ResearchGroup.builder()
                .name(request.getGroupName())
                .description(request.getGroupDescription())
                .mentorId(user.getId())
                .build();
        group = groupRepository.save(group);

        user.setGroupId(group.getId());
        user = userRepository.save(user);

        // 加入课题组关系表（导师为其课题组首名成员，与 user.group_id 一致）
        if (!groupMemberRepository.existsByGroupIdAndUserId(group.getId(), user.getId())) {
            groupMemberRepository.save(GroupMember.builder()
                    .groupId(group.getId())
                    .userId(user.getId())
                    .build());
        }

        log.info("[Auth] 导师注册成功: {} (组 {})", user.getUsername(), group.getName());

        // email-notification：导师注册成功后异步发送注册邮件（mandatory）
        sendRegisteredEmail(user, group.getName());
        return UserInfoDto.from(user, group.getName());
    }

    /**
     * 管理员邀请注册（管理员创建 ADMIN 邀请链接，被邀请人凭令牌注册为管理员）。
     * 注册表单复用学生注册字段（RegisterRequest：姓名/工号/密码/邮箱/手机），不建课题组。
     */
    @Transactional
    public UserInfoDto registerAdmin(RegisterRequest request) {
        RegistrationLink link = registrationLinkRepository.findByToken(request.getToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID));

        if (!"ADMIN".equals(link.getLinkType())) {
            throw new BusinessException(ErrorCode.REGISTRATION_LINK_INVALID, "该链接非管理员邀请链接");
        }
        if (!link.isValid()) {
            ErrorCode code = link.isExpired() ? ErrorCode.REGISTRATION_LINK_EXPIRED
                    : ErrorCode.REGISTRATION_LINK_INVALID;
            throw new BusinessException(code);
        }

        if (userRepository.existsByUsername(request.getStudentId())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "工号已被注册");
        }

        // 扣减次数
        link.setRemainingCount(link.getRemainingCount() - 1);
        if (link.getRemainingCount() <= 0) {
            link.setStatus("EXHAUSTED");
        }
        registrationLinkRepository.save(link);

        User user = User.builder()
                .username(request.getStudentId())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .realName(request.getRealName())
                .role(UserRole.ADMIN)
                .studentId(request.getStudentId())
                .email(request.getEmail())
                .phone(request.getPhone())
                .status("ACTIVE")
                .build();
        user = userRepository.save(user);

        log.info("[Auth] 管理员注册成功: {}", user.getUsername());

        // email-notification：管理员注册成功后异步发送注册邮件（mandatory）
        sendRegisteredEmail(user, null);
        return UserInfoDto.from(user, null);
    }

    /** 注册邮件：自助注册无登录上下文，操作人记"系统"；含课题组名与导师（如有）。 */
    private void sendRegisteredEmail(User user, String groupName) {
        if (user.getEmail() == null || user.getEmail().isBlank()) return;
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("operatorName", "系统");
        ctx.put("time", Instant.now());
        if (groupName != null) ctx.put("groupName", groupName);
        // 导师姓名（如课题组已指派导师）
        if (user.getGroupId() != null) {
            groupRepository.findById(user.getGroupId()).ifPresent(g -> {
                if (g.getMentorId() != null) {
                    userRepository.findById(g.getMentorId())
                            .map(User::getRealName).ifPresent(name -> ctx.put("mentorName", name));
                }
            });
        }
        emailService.sendAt(EmailTrigger.USER_REGISTERED, user, ctx);
    }

    public UserInfoDto getCurrentUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserInfoDto.from(user, resolveGroupName(user));
    }

    @Transactional
    public UserInfoDto updateProfile(Long userId, String realName, String email, String phone) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (realName != null) user.setRealName(realName);
        if (email != null) user.setEmail(email);
        if (phone != null) user.setPhone(phone);
        user = userRepository.save(user);
        return UserInfoDto.from(user, resolveGroupName(user));
    }

    private String resolveGroupName(User user) {
        if (user.getGroupId() == null) return null;
        return groupRepository.findById(user.getGroupId())
                .map(ResearchGroup::getName)
                .orElse(null);
    }
}
