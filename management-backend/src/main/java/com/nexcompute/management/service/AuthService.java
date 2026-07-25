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

/**
 * 认证服务（任务 2.2、3.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ResearchGroupRepository groupRepository;
    private final RegistrationLinkRepository registrationLinkRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
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

        // 加入课题组关系表
        String groupName = resolveGroupName(user);
        return UserInfoDto.from(user, groupName);
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
