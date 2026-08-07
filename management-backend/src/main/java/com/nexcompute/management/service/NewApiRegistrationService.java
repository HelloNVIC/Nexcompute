package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NewApiProperties;
import com.nexcompute.management.domain.NewApiInvitation;
import com.nexcompute.management.domain.NewApiRegistration;
import com.nexcompute.management.domain.NewApiRegistrationStatus;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.NewApiRegistrationApproveResponse;
import com.nexcompute.management.dto.NewApiRegistrationDetail;
import com.nexcompute.management.dto.NewApiRegistrationListItem;
import com.nexcompute.management.dto.NewApiRegisterFormMeta;
import com.nexcompute.management.dto.NewApiRegistrationSubmitResponse;
import com.nexcompute.management.dto.NewApiUsernameCheckResult;
import com.nexcompute.management.repository.NewApiInvitationRepository;
import com.nexcompute.management.repository.NewApiRegistrationRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NewAPI 注册申请服务（newapi-user-allocation D5/D2/D8），1:1 对照 nas-allocation 的 NasRegistrationService。
 * <ul>
 *   <li>公开 {@link #submit}：校验 token -> 用户名占用查重 -> 原子 redeem 名额 -> AES 加密密码 -> 写 PENDING 行（同事务，不调 NewAPI）</li>
 *   <li>管理员审批 {@link #approve}/{@link #reject}/{@link #reapprove}/{@link #reprovision}/{@link #refreshAllStatuses}/{@link #delete}：5 态机 PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND</li>
 * </ul>
 * 密码在 APPROVED/REJECTED/pending 过期后擦除；FAILED 保留可重试；NOT_FOUND 仅可重申上游。
 * <p>与 NAS 的差异：上游 NewAPI 建用户无 id 返回（建后 search 回查，D13）；改分组 PUT 须带 id+username（D14）；
 * group 为 String（如 default/vip）非角色组 id；NewAPI 用户无 unlimited_quota，额度走全局 QuotaForNewUser（D11）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewApiRegistrationService {

    /**
     * NewAPI 用户名规则（spike 待复核，见 Risks）：以字母开头，仅含字母/数字/下划线，3-64 字符。
     * NewAPI rc.22 字符集/长度边界未完全摸清，取保守规则；不满足者 approve 时 NewAPI 拒绝会暴露为 FAILED。
     */
    private static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,63}$");
    private static final Pattern PHONE_RE = Pattern.compile("^[0-9+][0-9 +()\\-]{4,19}$");
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_LETTER_RE = Pattern.compile("[A-Za-z]");
    private static final Pattern PASSWORD_DIGIT_RE = Pattern.compile("\\d");

    /** 用户名占用查重状态集（REJECTED 释放） */
    private static final Set<String> TAKEN_STATUSES = Set.of(
            NewApiRegistrationStatus.PENDING, NewApiRegistrationStatus.APPROVED, NewApiRegistrationStatus.FAILED);

    /** 详情合并的 NewAPI 用户字段 */
    private static final List<String> DETAIL_FIELDS = List.of(
            "id", "username", "display_name", "role", "status", "email", "group",
            "quota", "used_quota", "created_at", "last_login_at");

    private final NewApiRegistrationRepository registrationRepository;
    private final NewApiInvitationRepository invitationRepository;
    private final NasPasswordEncryptor passwordEncryptor;
    private final NewApiClient newApiClient;
    private final NewApiProperties newApiProperties;
    private final EmailService emailService;
    private final NewApiProvisionFailureRecorder failureRecorder;

    // ==================== 公开注册（7.x） ====================

    /** GET 表单元数据：校验 token 不消耗名额 */
    @Transactional(readOnly = true)
    public NewApiRegisterFormMeta validateForForm(String token) {
        NewApiInvitation inv = invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.NEWAPI_INVITATION_INVALID));
        if (inv.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.NEWAPI_INVITATION_REVOKED);
        }
        if (inv.isExpired()) {
            throw new BusinessException(ErrorCode.NEWAPI_INVITATION_EXPIRED);
        }
        if (inv.getUsedCount() >= inv.getMaxUses()) {
            throw new BusinessException(ErrorCode.NEWAPI_INVITATION_EXHAUSTED);
        }
        return new NewApiRegisterFormMeta(token, true, inv.getLabel(), inv.getRemaining(), inv.getExpiresAt());
    }

    /**
     * 用户名可用性检查（公开，输入用户名后 onBlur 实时调用）：本地占用 + NewAPI 查重。
     * NewAPI 不可达返回 UNREACHABLE（不阻断注册，前端提示用户）。
     */
    @Transactional(readOnly = true)
    public NewApiUsernameCheckResult checkUsername(String username) {
        if (username == null || !USERNAME_RE.matcher(username).matches()) {
            return new NewApiUsernameCheckResult(false, "INVALID",
                    "用户名须以字母开头，仅含字母/数字/下划线，3-64 字符");
        }
        if (registrationRepository.existsByUsernameAndStatusIn(username, TAKEN_STATUSES)) {
            return new NewApiUsernameCheckResult(false, "LOCAL_TAKEN", "用户名已被注册占用");
        }
        try {
            if (findExistingUserId(username) != null) {
                return new NewApiUsernameCheckResult(false, "NEWAPI_TAKEN", "NewAPI 已存在该用户名");
            }
        } catch (NewApiConnectionError e) {
            return new NewApiUsernameCheckResult(false, "UNREACHABLE", "NewAPI 不可达，暂无法检查");
        } catch (NewApiApiError e) {
            return new NewApiUsernameCheckResult(false, "ERROR", "检查失败：" + e.getMessage());
        }
        return new NewApiUsernameCheckResult(true, "AVAILABLE", "用户名可用");
    }

    /**
     * 公开提交注册申请：校验 -> 用户名占用查重 -> 原子 redeem 名额 -> AES 加密密码 -> 写 PENDING 行（同事务）。
     * 不调 NewAPI。
     */
    @Transactional
    public NewApiRegistrationSubmitResponse submit(String token, String username, String displayName,
                                                    String email, String phone, String password) {
        validateFields(username, displayName, email, phone, password);
        // 用户名占用查重：仅 PENDING/APPROVED/FAILED 占用，REJECTED 释放
        if (registrationRepository.existsByUsernameAndStatusIn(username, TAKEN_STATUSES)) {
            throw new BusinessException(ErrorCode.NEWAPI_USERNAME_TAKEN);
        }
        // 原子消耗名额（0 即名额耗尽/过期/撤销，精确分类）
        int affected = invitationRepository.redeem(token);
        if (affected == 0) {
            throw classifyRedeemFailure(token);
        }
        // redeem 经 @Modifying(clearAutomatically) 清空了持久化上下文，重新读取拿最新 usedCount 与 id
        NewApiInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.NEWAPI_INVITATION_INVALID));
        String passwordEnc = passwordEncryptor.encrypt(password);
        NewApiRegistration reg = NewApiRegistration.builder()
                .invitationId(invitation.getId())
                .username(username)
                .displayName(displayName)
                .email(email)
                .phone(phone)
                .passwordEnc(passwordEnc)
                .status(NewApiRegistrationStatus.PENDING)
                .build();
        reg = registrationRepository.save(reg);
        log.info("[NewAPI-Reg] 注册申请已提交: id={}, username={}, invitationId={}", reg.getId(), username, invitation.getId());
        notifySubmitted(reg);
        return new NewApiRegistrationSubmitResponse(reg.getId(), "申请已提交，待审批",
                newApiProperties.getBaseUrl());
    }

    // ==================== 管理员审批（8.x，5 态机） ====================

    /** 管理员列表（按 status 过滤，null=全部） */
    @Transactional(readOnly = true)
    public List<NewApiRegistrationListItem> list(String status) {
        requireAdmin();
        List<NewApiRegistration> regs = (status != null && !status.isBlank())
                ? registrationRepository.findByStatusOrderBySubmittedAtDesc(status.toUpperCase())
                : registrationRepository.findAllByOrderBySubmittedAtDesc();
        return regs.stream().map(this::toListItem).toList();
    }

    /** 管理员详情：本地行 + NewAPI 实时状态拼合，404 翻 NOT_FOUND */
    @Transactional
    public NewApiRegistrationDetail getDetail(Long id) {
        requireAdmin();
        NewApiRegistration reg = requireRegistration(id);
        Map<String, Object> newapiState = null;
        if (reg.getNewapiUserId() != null) {
            try {
                Map<String, Object> inst = newApiClient.userGetInstance(reg.getNewapiUserId());
                newapiState = new LinkedHashMap<>();
                for (String field : DETAIL_FIELDS) {
                    if (inst.containsKey(field)) {
                        newapiState.put(field, inst.get(field));
                    }
                }
                newapiState.put("available", true);
            } catch (NewApiApiError e) {
                // APPROVED 行 NewAPI 用户已删 -> 翻 NOT_FOUND（可重申上游）
                if (e.getHttpStatus() != null && e.getHttpStatus() == 404
                        && NewApiRegistrationStatus.APPROVED.equals(reg.getStatus())) {
                    reg.setStatus(NewApiRegistrationStatus.NOT_FOUND);
                    registrationRepository.save(reg);
                }
                newapiState = errorState(e.getMessage());
            } catch (NewApiConnectionError e) {
                newapiState = errorState(e.getMessage());
            }
        }
        return new NewApiRegistrationDetail(toListItem(reg), newapiState, newApiProperties.getBaseUrl());
    }

    /** 批准并开通：解密 -> 幂等查重 -> 建/回填 -> 擦密码 -> APPROVED；失败 FAILED 保留密码可重试 */
    @Audited(action = "NEWAPI_REGISTRATION_APPROVE", targetType = "NEWAPI_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NewApiRegistrationApproveResponse approve(Long id, String group) {
        requireAdmin();
        NewApiRegistration reg = requireRegistration(id);
        if (NewApiRegistrationStatus.APPROVED.equals(reg.getStatus())) {
            log.info("[NewAPI-Reg] 批准幂等（已 APPROVED）: id={}", id);
            return buildApproveResponse(reg, group, "已开通，前往 NewAPI 登录");
        }
        if (!NewApiRegistrationStatus.PENDING.equals(reg.getStatus()) && !NewApiRegistrationStatus.FAILED.equals(reg.getStatus())) {
            throw new BusinessException(ErrorCode.NEWAPI_REGISTRATION_INVALID_STATE, "申请状态为 " + reg.getStatus() + "，无法批准");
        }
        if (reg.getPasswordEnc() == null) {
            throw new BusinessException(ErrorCode.NEWAPI_NO_PASSWORD, "缺少暂存密码，无法开通（请重新提交申请）");
        }
        String password;
        try {
            password = passwordEncryptor.decrypt(reg.getPasswordEnc());
        } catch (NasCryptoException e) {
            throw new BusinessException(ErrorCode.NEWAPI_PASSWORD_DECRYPT_FAILED, "密码解密失败：" + e.getMessage());
        }
        Integer newapiUserId;
        try {
            newapiUserId = doProvision(reg.getUsername(), reg.getDisplayName(), reg.getEmail(), password, group);
        } catch (NewApiApiError | NewApiConnectionError e) {
            // 失败状态经 REQUIRES_NEW 独立事务落盘（置 FAILED + 写 provision_error，保留 password_enc），
            // 避免外层 @Transactional 回滚丢失（spec：开通失败保留密码可重试）
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            failureRecorder.recordFailure(reg.getId(), NewApiRegistrationStatus.FAILED, error);
            log.warn("[NewAPI-Reg] 批准开通失败: id={}, error={}", id, e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_PROVISION_FAILED, "NewAPI 开通失败：" + e.getMessage());
        }
        reg.setStatus(NewApiRegistrationStatus.APPROVED);
        reg.setNewapiUserId(newapiUserId);
        reg.setNewapiGroup(group);
        reg.setPasswordEnc(null);
        reg.setProvisionError(null);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NewAPI-Reg] 批准开通成功: id={}, newapiUserId={}", id, newapiUserId);
        notifyActivated(reg);
        return buildApproveResponse(reg, group, "已开通，前往 NewAPI 登录");
    }

    /** 拒绝：擦密码 -> REJECTED（不退名额，用户名释放） */
    @Audited(action = "NEWAPI_REGISTRATION_REJECT", targetType = "NEWAPI_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NewApiRegistrationListItem reject(Long id, String rejectReason) {
        requireAdmin();
        NewApiRegistration reg = requireRegistration(id);
        if (!NewApiRegistrationStatus.PENDING.equals(reg.getStatus())) {
            throw new BusinessException(ErrorCode.NEWAPI_REGISTRATION_INVALID_STATE, "申请状态为 " + reg.getStatus() + "，无法拒绝");
        }
        reg.setStatus(NewApiRegistrationStatus.REJECTED);
        reg.setPasswordEnc(null);
        reg.setRejectReason(rejectReason);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NewAPI-Reg] 已拒绝: id={}", id);
        notifyRejected(reg, rejectReason);
        return toListItem(reg);
    }

    /** 改分组（已 APPROVED）：PUT /api/user/ 改 group（body 含 id+username），不改 quota（D14） */
    @Audited(action = "NEWAPI_REGISTRATION_REAPPROVE", targetType = "NEWAPI_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NewApiRegistrationApproveResponse reapprove(Long id, String group) {
        requireAdmin();
        NewApiRegistration reg = requireRegistration(id);
        if (!NewApiRegistrationStatus.APPROVED.equals(reg.getStatus()) || reg.getNewapiUserId() == null) {
            throw new BusinessException(ErrorCode.NEWAPI_REGISTRATION_INVALID_STATE, "仅已开通(APPROVED)的申请可改分组");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (group != null && !group.isBlank()) {
            fields.put("group", group);
        }
        newApiClient.userUpdate(reg.getNewapiUserId(), reg.getUsername(), fields);
        reg.setNewapiGroup(group);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NewAPI-Reg] 改分组: id={}, newapiUserId={}, group={}", id, reg.getNewapiUserId(), group);
        return buildApproveResponse(reg, group, "分组已更新");
    }

    /** 重申上游（NOT_FOUND）：用新密码幂等查重/建用户，成功置 APPROVED；失败保留 NOT_FOUND 可重试 */
    @Audited(action = "NEWAPI_REGISTRATION_REPROVISION", targetType = "NEWAPI_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NewApiRegistrationApproveResponse reprovision(Long id, String password, String group) {
        requireAdmin();
        NewApiRegistration reg = requireRegistration(id);
        if (!NewApiRegistrationStatus.NOT_FOUND.equals(reg.getStatus())) {
            throw new BusinessException(ErrorCode.NEWAPI_REGISTRATION_INVALID_STATE, "仅'用户不存在'状态可重申上游");
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请设置新密码");
        }
        validatePassword(password);
        Integer newapiUserId;
        try {
            newapiUserId = doProvision(reg.getUsername(), reg.getDisplayName(), reg.getEmail(), password, group);
        } catch (NewApiApiError | NewApiConnectionError e) {
            // 失败状态经 REQUIRES_NEW 独立事务落盘（保持 NOT_FOUND + 写 provision_error，保留 password_enc）
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            failureRecorder.recordFailure(reg.getId(), NewApiRegistrationStatus.NOT_FOUND, error);
            log.warn("[NewAPI-Reg] 重申上游失败: id={}, error={}", id, e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_PROVISION_FAILED, "NewAPI 重新开通失败：" + e.getMessage());
        }
        reg.setStatus(NewApiRegistrationStatus.APPROVED);
        reg.setNewapiUserId(newapiUserId);
        reg.setNewapiGroup(group);
        reg.setPasswordEnc(null);
        reg.setProvisionError(null);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NewAPI-Reg] 重申上游成功: id={}, newapiUserId={}", id, newapiUserId);
        notifyActivated(reg);
        return buildApproveResponse(reg, group, "已重新开通");
    }

    /** 批量刷新已开通用户状态：404 翻 NOT_FOUND，网络错误不翻 */
    @Audited(action = "NEWAPI_REGISTRATION_REFRESH_STATUSES", targetType = "NEWAPI_REGISTRATION")
    @Transactional
    public Map<String, Integer> refreshAllStatuses() {
        requireAdmin();
        List<NewApiRegistration> regs = registrationRepository
                .findByStatusAndNewapiUserIdIsNotNull(NewApiRegistrationStatus.APPROVED);
        int flipped = 0;
        for (NewApiRegistration reg : regs) {
            try {
                newApiClient.userGetInstance(reg.getNewapiUserId());
            } catch (NewApiApiError e) {
                if (e.getHttpStatus() != null && e.getHttpStatus() == 404) {
                    reg.setStatus(NewApiRegistrationStatus.NOT_FOUND);
                    registrationRepository.save(reg);
                    flipped++;
                }
            } catch (NewApiConnectionError e) {
                // NewAPI 不可达，保持原状态不翻转
            }
        }
        log.info("[NewAPI-Reg] 状态刷新: checked={}, flipped={}", regs.size(), flipped);
        return Map.of("checked", regs.size(), "flipped", flipped);
    }

    /** 删除记录（不删 NewAPI 用户） */
    @Audited(action = "NEWAPI_REGISTRATION_DELETE", targetType = "NEWAPI_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public void delete(Long id) {
        requireAdmin();
        NewApiRegistration reg = requireRegistration(id);
        registrationRepository.delete(reg);
        log.info("[NewAPI-Reg] 记录已删除（不删 NewAPI 用户）: id={}, username={}, status={}",
                id, reg.getUsername(), reg.getStatus());
    }

    // ==================== 内部辅助 ====================

    /** 审批/重申上游开通成功后异步通知 NewAPI 账号邮箱（失败仅记日志，不影响审批事务） */
    private void notifyActivated(NewApiRegistration reg) {
        try {
            emailService.sendNewApiActivated(reg.getEmail(), reg.getUsername(), reg.getDisplayName(),
                    newApiProperties.getBaseUrl());
        } catch (Exception e) {
            log.warn("[NewAPI-Reg] 激活通知触发失败(不影响审批): id={}, err={}", reg.getId(), e.getMessage());
        }
    }

    /** 提交注册申请后异步通知申请者邮箱（已收到待审批，失败仅记日志） */
    private void notifySubmitted(NewApiRegistration reg) {
        try {
            emailService.sendNewApiSubmitted(reg.getEmail(), reg.getUsername(), reg.getDisplayName());
        } catch (Exception e) {
            log.warn("[NewAPI-Reg] 提交通知触发失败(不影响提交): id={}, err={}", reg.getId(), e.getMessage());
        }
    }

    /** 拒绝后异步通知申请者邮箱（含原因，失败仅记日志） */
    private void notifyRejected(NewApiRegistration reg, String reason) {
        try {
            emailService.sendNewApiRejected(reg.getEmail(), reg.getUsername(), reg.getDisplayName(), reason);
        } catch (Exception e) {
            log.warn("[NewAPI-Reg] 拒绝通知触发失败(不影响拒绝): id={}, err={}", reg.getId(), e.getMessage());
        }
    }

    /**
     * NewAPI 幂等查重 + 建用户，返回 newapi_user_id；失败抛 NewApi*Error。
     * 已存在则回填 id（不重复创建）；不存在则 userCreate（POST + search 回查拿 id，D13）。
     */
    private Integer doProvision(String username, String displayName, String email, String password, String group) {
        Integer existing = findExistingUserId(username);
        if (existing != null) {
            log.info("[NewAPI] 用户已存在，回填: username={}", username);
            return existing;
        }
        return newApiClient.userCreate(username, password, displayName, email, group);
    }

    /** 按 username 精确匹配查 NewAPI 用户 id；无精确匹配返回 null */
    private Integer findExistingUserId(String username) {
        for (Map<String, Object> u : newApiClient.userSearch(username)) {
            Object name = u.get("username");
            if (name != null && name.toString().equals(username)) {
                Object id = u.get("id");
                if (id instanceof Number n) {
                    return n.intValue();
                }
            }
        }
        return null;
    }

    private void validateFields(String username, String displayName, String email, String phone, String password) {
        if (username == null || !USERNAME_RE.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "username 须以字母开头，仅含字母、数字、下划线，3-64 字符");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "显示名必填");
        }
        if (displayName.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "显示名过长");
        }
        if (email == null || !EMAIL_RE.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        if (phone == null || !PHONE_RE.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        validatePassword(password);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码至少 8 位");
        }
        if (!PASSWORD_LETTER_RE.matcher(password).find() || !PASSWORD_DIGIT_RE.matcher(password).find()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码必须同时包含字母和数字");
        }
    }

    /** redeem 返回 0 时再读邀请状态精确分类：无效/撤销/过期/耗尽 */
    private BusinessException classifyRedeemFailure(String token) {
        Optional<NewApiInvitation> opt = invitationRepository.findByToken(token);
        if (opt.isEmpty()) {
            return new BusinessException(ErrorCode.NEWAPI_INVITATION_INVALID);
        }
        NewApiInvitation inv = opt.get();
        if (inv.getRevokedAt() != null) {
            return new BusinessException(ErrorCode.NEWAPI_INVITATION_REVOKED);
        }
        if (inv.isExpired()) {
            return new BusinessException(ErrorCode.NEWAPI_INVITATION_EXPIRED);
        }
        if (inv.getUsedCount() >= inv.getMaxUses()) {
            return new BusinessException(ErrorCode.NEWAPI_INVITATION_EXHAUSTED);
        }
        return new BusinessException(ErrorCode.NEWAPI_INVITATION_EXHAUSTED);
    }

    private NewApiRegistration requireRegistration(Long id) {
        return registrationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NEWAPI_REGISTRATION_NOT_FOUND));
    }

    private NewApiRegistrationListItem toListItem(NewApiRegistration reg) {
        return new NewApiRegistrationListItem(
                reg.getId(), reg.getInvitationId(), reg.getUsername(), reg.getDisplayName(),
                reg.getEmail(), reg.getPhone(), reg.getStatus(), reg.getNewapiUserId(),
                reg.getNewapiGroup(), reg.getSubmittedAt(), reg.getReviewedBy(), reg.getReviewedAt(),
                reg.getRejectReason(), reg.getProvisionError(), reg.getPasswordEnc() != null
        );
    }

    private NewApiRegistrationApproveResponse buildApproveResponse(NewApiRegistration reg, String group, String message) {
        return new NewApiRegistrationApproveResponse(
                message, reg.getId(), reg.getStatus(), reg.getNewapiUserId(),
                newApiProperties.getBaseUrl(), group);
    }

    private static Map<String, Object> errorState(String message) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("available", false);
        state.put("error", message);
        return state;
    }

    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }
}
