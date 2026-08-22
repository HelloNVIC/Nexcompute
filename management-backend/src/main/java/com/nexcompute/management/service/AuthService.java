package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.PasswordResetProperties;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.dto.*;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

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
    private final PasswordResetOtpRepository passwordResetOtpRepository;
    private final PasswordResetOtpRecorder passwordResetOtpRecorder;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final PasswordResetProperties passwordResetProperties;

    /** 密码强度正则：≥6 位且含字母与数字（与注册 DTO 一致，服务端双保险防绕过 @Valid） */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[0-9])(?=.*[a-zA-Z]).{6,}$");

    /** 忘记密码统一成功消息（防账号枚举，D4：账号不存在/禁用/无邮箱/限频均返回此消息） */
    public static final String PASSWORD_RESET_SENT_MESSAGE = "若账号存在，验证码已发送至其绑定邮箱";

    /** 验证码随机数生成器（6 位数字码，密码学安全） */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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

    /**
     * 校验注册链接有效性（不消耗名额）。
     * 学生 / 导师 / 管理员邀请链接共用 RegistrationLink，凭 linkType 区分；
     * 供注册页打开时直接判断是否显示表单或失效提示（platform-refinements：链接失效直接提示）。
     */
    @Transactional(readOnly = true)
    public RegistrationLinkValidateResult validateRegistrationLink(String token, String linkType) {
        RegistrationLink link = registrationLinkRepository.findByToken(token).orElse(null);
        if (link == null) {
            return RegistrationLinkValidateResult.builder()
                    .valid(false).linkType(linkType).reason("注册链接无效或不存在").build();
        }
        if (linkType == null || !linkType.equals(link.getLinkType())) {
            return RegistrationLinkValidateResult.builder()
                    .valid(false).linkType(linkType).reason("注册链接类型不匹配").build();
        }
        if (link.isValid()) {
            return RegistrationLinkValidateResult.builder()
                    .valid(true).linkType(link.getLinkType())
                    .remaining(link.getRemainingCount())
                    .expiresAt(link.getExpireAt())
                    .build();
        }
        // 失效：给出具体原因（作废 / 名额用尽 / 已过期 / 其他）
        String reason;
        if ("REVOKED".equals(link.getStatus())) {
            reason = "注册链接已被作废";
        } else if (link.getRemainingCount() != null && link.getRemainingCount() <= 0) {
            reason = "注册链接名额已用尽";
        } else if (link.isExpired()) {
            reason = "注册链接已过期";
        } else {
            reason = "注册链接无效或已失效";
        }
        return RegistrationLinkValidateResult.builder()
                .valid(false).linkType(linkType).reason(reason).build();
    }

    /**
     * 用户自助修改密码（password-management-and-id-validation D6/D7）。
     * 校验旧密码匹配 -> 新密码强度 -> 新旧不同 -> BCrypt 重新哈希落库。
     * 恒审计由控制器 @Audited(action=USER_CHANGE_PASSWORD, force=true) 完成。
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_INCORRECT);
        }
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_OLD);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("[Auth] 用户修改密码成功: {}", user.getUsername());
    }

    /**
     * 忘记密码-发送验证码（password-management-and-id-validation D3/D4/D8/D11）。
     * 防账号枚举：账号不存在 / 禁用 / 无邮箱 -> 直接返回统一消息（不建码不发邮件）；
     * 限频命中抛 PASSWORD_RESET_SEND_TOO_FREQUENT（由控制器捕获后仍返回统一消息，D4）；
     * 账号存在 + ACTIVE + 有邮箱 + 未限频 -> 生成 6 位数字码 BCrypt 哈希入库 + 异步发邮件。
     * 返回统一消息字符串。
     */
    @Transactional
    public String sendPasswordResetCode(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isActive()
                || user.getEmail() == null || user.getEmail().isBlank()) {
            return PASSWORD_RESET_SENT_MESSAGE; // D4/D11：统一消息，不建码不发邮件
        }
        // 限频：同用户名冷却窗口内已建码则不重复建码（D8）
        Instant cooldownSince = Instant.now()
                .minus(passwordResetProperties.getSendCooldownSeconds(), ChronoUnit.SECONDS);
        if (passwordResetOtpRepository.existsByUsernameAndCreatedAtAfter(username, cooldownSince)) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_SEND_TOO_FREQUENT);
        }
        // 生成 6 位数字码 -> BCrypt 哈希 -> 入库
        String code = generate6DigitCode();
        Instant expiresAt = Instant.now()
                .plus(passwordResetProperties.getCodeExpireMinutes(), ChronoUnit.MINUTES);
        passwordResetOtpRepository.save(PasswordResetOtp.builder()
                .username(username)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(expiresAt)
                .build());
        // 异步发邮件（@Async 不阻断业务事务；失败仅记 email_log，D4 仍返回统一成功）
        emailService.sendPasswordResetCode(user.getEmail(), user.getUsername(), user.getRealName(),
                code, passwordResetProperties.getCodeExpireMinutes());
        log.info("[Auth] 忘记密码验证码已发送: username={}", username);
        return PASSWORD_RESET_SENT_MESSAGE;
    }

    /**
     * 忘记密码-重置（password-management-and-id-validation D2/D7/D8）。
     * 取最近一行验证码 -> 无码/已消费 -> NOT_FOUND -> 达尝试上限 -> TOO_MANY_ATTEMPTS ->
     * 已过期 -> EXPIRED -> 哈希不匹配 attempt_count+1（达上限则置 consumed_at 改抛 TOO_MANY_ATTEMPTS）->
     * 成功则新密码强度校验 + encode 落库 app_user + 置 consumed_at。
     */
    @Transactional
    public void resetPassword(String username, String code, String newPassword) {
        PasswordResetOtp otp = passwordResetOtpRepository
                .findFirstByUsernameOrderByCreatedAtDesc(username).orElse(null);
        if (otp == null || otp.isConsumed()) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_OTP_NOT_FOUND);
        }
        int maxAttempts = passwordResetProperties.getMaxAttempts();
        if (otp.isAttemptsExhausted(maxAttempts)) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_OTP_TOO_MANY_ATTEMPTS);
        }
        if (otp.isExpired()) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_OTP_EXPIRED);
        }
        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            // 验证码错误：经独立事务（REQUIRES_NEW）递增 attempt_count，防外层事务回滚丢失（provision-failure-rollback pitfall）
            int newAttempts = passwordResetOtpRecorder.recordFailedAttempt(otp.getId(), maxAttempts);
            if (newAttempts >= maxAttempts) {
                throw new BusinessException(ErrorCode.PASSWORD_RESET_OTP_TOO_MANY_ATTEMPTS);
            }
            throw new BusinessException(ErrorCode.PASSWORD_RESET_OTP_INVALID);
        }
        // 验证码正确：新密码强度校验 -> 重置落库 -> 置 consumed_at
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        otp.setConsumedAt(Instant.now());
        passwordResetOtpRepository.save(otp);
        log.info("[Auth] 忘记密码重置成功: {}", username);
    }

    /**
     * 邀请注册页工号/学号可用性校验（password-management-and-id-validation D5）。
     * 先校验邀请链接有效（防开放枚举），valid=false 返回不可用 + 邀请链接无效（不查重）；
     * valid=true 则 existsByUsername 返回可用性（跨角色全局查重）。
     */
    @Transactional(readOnly = true)
    public UsernameAvailabilityResult checkUsernameAvailable(String token, String linkType, String studentId) {
        RegistrationLinkValidateResult linkResult = validateRegistrationLink(token, linkType);
        if (!linkResult.isValid()) {
            return UsernameAvailabilityResult.builder()
                    .available(false).reason("邀请链接无效").build();
        }
        boolean available = !userRepository.existsByUsername(studentId);
        return UsernameAvailabilityResult.builder().available(available).build();
    }

    /** 生成 6 位数字验证码（前导零补齐） */
    private static String generate6DigitCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
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
