package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NasProperties;
import com.nexcompute.management.domain.NasInvitation;
import com.nexcompute.management.domain.NasRegistration;
import com.nexcompute.management.domain.NasRegistrationStatus;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.NasRegistrationApproveResponse;
import com.nexcompute.management.dto.NasRegistrationDetail;
import com.nexcompute.management.dto.NasRegistrationListItem;
import com.nexcompute.management.dto.NasRegisterFormMeta;
import com.nexcompute.management.dto.NasRegistrationSubmitResponse;
import com.nexcompute.management.dto.NasUsernameCheckResult;
import com.nexcompute.management.repository.NasInvitationRepository;
import com.nexcompute.management.repository.NasRegistrationRepository;
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
 * NAS 注册申请服务（nas-allocation D5/D2/D8），1:1 对照原 Python 门户 registrations.py。
 * <ul>
 *   <li>公开 {@link #submit}：校验 token -> 用户名占用查重 -> 原子 redeem 名额 -> AES 加密密码 -> 写 PENDING 行（同事务，不调 TrueNAS）</li>
 *   <li>管理员审批 {@link #approve}/{@link #reject}/{@link #reapprove}/{@link #reprovision}/{@link #refreshAllStatuses}/{@link #delete}：5 态机 PENDING/APPROVED/REJECTED/FAILED/NOT_FOUND</li>
 * </ul>
 * 密码在 APPROVED/REJECTED/pending 过期后擦除；FAILED 保留可重试；NOT_FOUND 仅可重申上游。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NasRegistrationService {

    /** TrueNAS Web 后台角色组 id（builtin）：完全/只读/共享管理员 */
    public static final Integer FULL_ADMIN = 40;
    public static final Integer READONLY_ADMIN = 41;
    public static final Integer SHARING_ADMIN = 42;

    private static final Set<Integer> ROLE_GROUPS = Set.of(FULL_ADMIN, READONLY_ADMIN, SHARING_ADMIN);
    private static final Map<Integer, String> ROLE_FOR_GROUP = Map.of(
            FULL_ADMIN, "FULL_ADMIN", READONLY_ADMIN, "READONLY_ADMIN", SHARING_ADMIN, "SHARING_ADMIN");
    /** 详情合并的 TrueNAS 用户字段 */
    private static final List<String> DETAIL_FIELDS = List.of(
            "id", "uid", "username", "full_name", "locked", "smb", "local", "email",
            "last_password_change", "password_age", "password_disabled", "ssh_password_enabled", "groups", "roles");

    // 对照 TrueNAS user.create 校验：必须以字母开头（TrueNAS 拒绝以数字/下划线/./- 开头，报 "Cannot start with 'x'"）
    private static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,31}$");
    private static final Pattern PHONE_RE = Pattern.compile("^[0-9+][0-9 +()\\-]{4,19}$");
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_LETTER_RE = Pattern.compile("[A-Za-z]");
    private static final Pattern PASSWORD_DIGIT_RE = Pattern.compile("\\d");

    private final NasRegistrationRepository registrationRepository;
    private final NasInvitationRepository invitationRepository;
    private final NasPasswordEncryptor passwordEncryptor;
    private final TrueNasClient trueNasClient;
    private final NasProperties nasProperties;
    private final EmailService emailService;

    // ==================== 公开注册（7.x） ====================

    /** GET 表单元数据：校验 token 不消耗名额 */
    @Transactional(readOnly = true)
    public NasRegisterFormMeta validateForForm(String token) {
        NasInvitation inv = invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.NAS_INVITATION_INVALID));
        if (inv.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.NAS_INVITATION_REVOKED);
        }
        if (inv.isExpired()) {
            throw new BusinessException(ErrorCode.NAS_INVITATION_EXPIRED);
        }
        if (inv.getUsedCount() >= inv.getMaxUses()) {
            throw new BusinessException(ErrorCode.NAS_INVITATION_EXHAUSTED);
        }
        return new NasRegisterFormMeta(token, true, inv.getLabel(), inv.getRemaining(), inv.getExpiresAt());
    }

    /**
     * 用户名可用性检查（公开，输入用户名后 onBlur 实时调用）：本地占用 + TrueNAS 查重。
     * TrueNAS 不可达返回 UNREACHABLE（不阻断注册，前端提示用户）。
     */
    @Transactional(readOnly = true)
    public NasUsernameCheckResult checkUsername(String username) {
        if (username == null || !USERNAME_RE.matcher(username).matches()) {
            return new NasUsernameCheckResult(false, "INVALID",
                    "用户名必须以字母开头，仅含字母/数字/下划线/点/连字符，<=32 字符");
        }
        if (registrationRepository.existsByUsernameAndStatusIn(username, List.of(
                NasRegistrationStatus.PENDING, NasRegistrationStatus.APPROVED, NasRegistrationStatus.FAILED))) {
            return new NasUsernameCheckResult(false, "LOCAL_TAKEN", "用户名已被注册占用");
        }
        try {
            Map<String, Object> existing = trueNasClient.userFindByUsername(username);
            if (existing != null) {
                return new NasUsernameCheckResult(false, "TRUENAS_TAKEN", "TrueNAS 已存在该用户名");
            }
        } catch (TrueNasConnectionError e) {
            return new NasUsernameCheckResult(false, "UNREACHABLE", "TrueNAS 不可达，暂无法检查");
        } catch (TrueNasApiError e) {
            return new NasUsernameCheckResult(false, "ERROR", "检查失败：" + e.getMessage());
        }
        return new NasUsernameCheckResult(true, "AVAILABLE", "用户名可用");
    }

    /**
     * 公开提交注册申请：校验 -> 用户名占用查重 -> 原子 redeem 名额 -> AES 加密密码 -> 写 PENDING 行（同事务）。
     * 不调 TrueNAS。
     */
    @Transactional
    public NasRegistrationSubmitResponse submit(String token, String username, String fullName,
                                                String email, String phone, String password) {
        validateFields(username, fullName, email, phone, password);
        // 用户名占用查重：仅 PENDING/APPROVED/FAILED 占用，REJECTED 释放
        if (registrationRepository.existsByUsernameAndStatusIn(username, List.of(
                NasRegistrationStatus.PENDING, NasRegistrationStatus.APPROVED, NasRegistrationStatus.FAILED))) {
            throw new BusinessException(ErrorCode.NAS_USERNAME_TAKEN);
        }
        // 原子消耗名额（0 即名额耗尽/过期/撤销，精确分类）
        int affected = invitationRepository.redeem(token);
        if (affected == 0) {
            throw classifyRedeemFailure(token);
        }
        // redeem 经 @Modifying(clearAutomatically) 清空了持久化上下文，重新读取拿最新 usedCount 与 id
        NasInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.NAS_INVITATION_INVALID));
        String passwordEnc = passwordEncryptor.encrypt(password);
        NasRegistration reg = NasRegistration.builder()
                .invitationId(invitation.getId())
                .username(username)
                .fullName(fullName)
                .email(email)
                .phone(phone)
                .passwordEnc(passwordEnc)
                .status(NasRegistrationStatus.PENDING)
                .build();
        reg = registrationRepository.save(reg);
        log.info("[NAS-Reg] 注册申请已提交: id={}, username={}, invitationId={}", reg.getId(), username, invitation.getId());
        notifySubmitted(reg);
        return new NasRegistrationSubmitResponse(reg.getId(), "申请已提交，待审批",
                nasProperties.getTruenas().getBaseUrl());
    }

    // ==================== 管理员审批（8.x，5 态机） ====================

    /** 管理员列表（按 status 过滤，null=全部） */
    @Transactional(readOnly = true)
    public List<NasRegistrationListItem> list(String status) {
        requireAdmin();
        List<NasRegistration> regs = (status != null && !status.isBlank())
                ? registrationRepository.findByStatusOrderBySubmittedAtDesc(status.toUpperCase())
                : registrationRepository.findAllByOrderBySubmittedAtDesc();
        return regs.stream().map(this::toListItem).toList();
    }

    /** 管理员详情：本地行 + TrueNAS 实时状态拼合，404 翻 NOT_FOUND */
    @Transactional
    public NasRegistrationDetail getDetail(Long id) {
        requireAdmin();
        NasRegistration reg = requireRegistration(id);
        Map<String, Object> truenasState = null;
        if (reg.getTruenasUserId() != null) {
            try {
                Map<String, Object> inst = trueNasClient.userGetInstance(reg.getTruenasUserId());
                truenasState = new LinkedHashMap<>();
                for (String field : DETAIL_FIELDS) {
                    if (inst.containsKey(field)) {
                        truenasState.put(field, inst.get(field));
                    }
                }
                truenasState.put("available", true);
            } catch (TrueNasApiError e) {
                // APPROVED 行 TrueNAS 用户已删 -> 翻 NOT_FOUND（可重申上游）
                if (e.getHttpStatus() != null && e.getHttpStatus() == 404
                        && NasRegistrationStatus.APPROVED.equals(reg.getStatus())) {
                    reg.setStatus(NasRegistrationStatus.NOT_FOUND);
                    registrationRepository.save(reg);
                }
                truenasState = errorState(e.getMessage());
            } catch (TrueNasConnectionError e) {
                truenasState = errorState(e.getMessage());
            }
        }
        return new NasRegistrationDetail(toListItem(reg), truenasState, nasProperties.getTruenas().getBaseUrl());
    }

    /** 批准并开通：解密 -> 幂等查重 -> 建/回填 -> 擦密码 -> APPROVED；失败 FAILED 保留密码可重试 */
    @Audited(action = "NAS_REGISTRATION_APPROVE", targetType = "NAS_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NasRegistrationApproveResponse approve(Long id, Integer webuiGroupId) {
        requireAdmin();
        NasRegistration reg = requireRegistration(id);
        if (NasRegistrationStatus.APPROVED.equals(reg.getStatus())) {
            log.info("[NAS-Reg] 批准幂等（已 APPROVED）: id={}", id);
            return buildApproveResponse(reg, webuiGroupId, "已开通，前往 TrueNAS 登录");
        }
        if (!NasRegistrationStatus.PENDING.equals(reg.getStatus()) && !NasRegistrationStatus.FAILED.equals(reg.getStatus())) {
            throw new BusinessException(ErrorCode.NAS_REGISTRATION_INVALID_STATE, "申请状态为 " + reg.getStatus() + "，无法批准");
        }
        if (reg.getPasswordEnc() == null) {
            throw new BusinessException(ErrorCode.NAS_NO_PASSWORD, "缺少暂存密码，无法开通（请重新提交申请）");
        }
        String password;
        try {
            password = passwordEncryptor.decrypt(reg.getPasswordEnc());
        } catch (NasCryptoException e) {
            throw new BusinessException(ErrorCode.NAS_PASSWORD_DECRYPT_FAILED, "密码解密失败：" + e.getMessage());
        }
        Integer truenasUserId;
        Integer truenasUid;
        try {
            Integer[] ids = doProvision(reg.getUsername(), reg.getFullName(), reg.getEmail(), password,
                    nasProperties.getTruenas().getUserHomeParent(), webuiGroupId);
            truenasUserId = ids[0];
            truenasUid = ids[1];
        } catch (TrueNasApiError | TrueNasConnectionError e) {
            reg.setStatus(NasRegistrationStatus.FAILED);
            reg.setProvisionError(e.getClass().getSimpleName() + ": " + e.getMessage());
            // password_enc 保留供重试
            registrationRepository.save(reg);
            log.warn("[NAS-Reg] 批准开通失败: id={}, error={}", id, e.getMessage());
            throw new BusinessException(ErrorCode.NAS_PROVISION_FAILED, "TrueNAS 开通失败：" + e.getMessage());
        }
        reg.setStatus(NasRegistrationStatus.APPROVED);
        reg.setTruenasUserId(truenasUserId);
        reg.setTruenasUid(truenasUid);
        reg.setPasswordEnc(null);
        reg.setProvisionError(null);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NAS-Reg] 批准开通成功: id={}, truenasUserId={}", id, truenasUserId);
        notifyActivated(reg);
        return buildApproveResponse(reg, webuiGroupId, "已开通，前往 TrueNAS 登录");
    }

    /** 拒绝：擦密码 -> REJECTED（不退名额，用户名释放） */
    @Audited(action = "NAS_REGISTRATION_REJECT", targetType = "NAS_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NasRegistrationListItem reject(Long id, String rejectReason) {
        requireAdmin();
        NasRegistration reg = requireRegistration(id);
        if (!NasRegistrationStatus.PENDING.equals(reg.getStatus())) {
            throw new BusinessException(ErrorCode.NAS_REGISTRATION_INVALID_STATE, "申请状态为 " + reg.getStatus() + "，无法拒绝");
        }
        reg.setStatus(NasRegistrationStatus.REJECTED);
        reg.setPasswordEnc(null);
        reg.setRejectReason(rejectReason);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NAS-Reg] 已拒绝: id={}", id);
        notifyRejected(reg, rejectReason);
        return toListItem(reg);
    }

    /** 改角色（已 APPROVED）：更新 TrueNAS 附加组，移除 40/41/42 加入新组，保留 builtin_users */
    @Audited(action = "NAS_REGISTRATION_REAPPROVE", targetType = "NAS_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NasRegistrationApproveResponse reapprove(Long id, Integer webuiGroupId) {
        requireAdmin();
        NasRegistration reg = requireRegistration(id);
        if (!NasRegistrationStatus.APPROVED.equals(reg.getStatus()) || reg.getTruenasUserId() == null) {
            throw new BusinessException(ErrorCode.NAS_REGISTRATION_INVALID_STATE, "仅已开通(APPROVED)的申请可重新选择角色");
        }
        Map<String, Object> inst = trueNasClient.userGetInstance(reg.getTruenasUserId());
        List<Integer> currentGroups = extractGroups(inst.get("groups"));
        // 移除现有角色组，保留其余（如 builtin_users），加入新组
        List<Integer> newGroups = new java.util.ArrayList<>(currentGroups.stream()
                .filter(g -> !ROLE_GROUPS.contains(g))
                .toList());
        if (webuiGroupId != null) {
            newGroups.add(webuiGroupId);
        }
        trueNasClient.userUpdate(reg.getTruenasUserId(), Map.of("groups", newGroups));
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NAS-Reg] 改角色: id={}, truenasUserId={}, webuiGroupId={}", id, reg.getTruenasUserId(), webuiGroupId);
        return buildApproveResponse(reg, webuiGroupId, "角色已更新");
    }

    /** 重申上游（NOT_FOUND）：用新密码幂等查重/建用户，成功置 APPROVED；失败保留 NOT_FOUND 可重试 */
    @Audited(action = "NAS_REGISTRATION_REPROVISION", targetType = "NAS_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public NasRegistrationApproveResponse reprovision(Long id, String password, Integer webuiGroupId) {
        requireAdmin();
        NasRegistration reg = requireRegistration(id);
        if (!NasRegistrationStatus.NOT_FOUND.equals(reg.getStatus())) {
            throw new BusinessException(ErrorCode.NAS_REGISTRATION_INVALID_STATE, "仅'用户不存在'状态可重申上游");
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请设置新密码");
        }
        validatePassword(password);
        Integer truenasUserId;
        Integer truenasUid;
        try {
            Integer[] ids = doProvision(reg.getUsername(), reg.getFullName(), reg.getEmail(), password,
                    nasProperties.getTruenas().getUserHomeParent(), webuiGroupId);
            truenasUserId = ids[0];
            truenasUid = ids[1];
        } catch (TrueNasApiError | TrueNasConnectionError e) {
            reg.setProvisionError(e.getClass().getSimpleName() + ": " + e.getMessage());
            registrationRepository.save(reg);
            log.warn("[NAS-Reg] 重申上游失败: id={}, error={}", id, e.getMessage());
            throw new BusinessException(ErrorCode.NAS_PROVISION_FAILED, "TrueNAS 重新注册失败：" + e.getMessage());
        }
        reg.setStatus(NasRegistrationStatus.APPROVED);
        reg.setTruenasUserId(truenasUserId);
        reg.setTruenasUid(truenasUid);
        reg.setPasswordEnc(null);
        reg.setProvisionError(null);
        reg.setReviewedBy(SecurityUtils.getCurrentUserId());
        reg.setReviewedAt(Instant.now());
        registrationRepository.save(reg);
        log.info("[NAS-Reg] 重申上游成功: id={}, truenasUserId={}", id, truenasUserId);
        notifyActivated(reg);
        return buildApproveResponse(reg, webuiGroupId, "已重新注册");
    }

    /** 批量刷新已开通用户状态：404 翻 NOT_FOUND，网络错误不翻 */
    @Audited(action = "NAS_REGISTRATION_REFRESH_STATUSES", targetType = "NAS_REGISTRATION")
    @Transactional
    public Map<String, Integer> refreshAllStatuses() {
        requireAdmin();
        List<NasRegistration> regs = registrationRepository
                .findByStatusAndTruenasUserIdIsNotNull(NasRegistrationStatus.APPROVED);
        int flipped = 0;
        for (NasRegistration reg : regs) {
            try {
                trueNasClient.userGetInstance(reg.getTruenasUserId());
            } catch (TrueNasApiError e) {
                if (e.getHttpStatus() != null && e.getHttpStatus() == 404) {
                    reg.setStatus(NasRegistrationStatus.NOT_FOUND);
                    registrationRepository.save(reg);
                    flipped++;
                }
            } catch (TrueNasConnectionError e) {
                // TrueNAS 不可达，保持原状态不翻转
            }
        }
        log.info("[NAS-Reg] 状态刷新: checked={}, flipped={}", regs.size(), flipped);
        return Map.of("checked", regs.size(), "flipped", flipped);
    }

    /** 删除记录（不删 TrueNAS 用户） */
    @Audited(action = "NAS_REGISTRATION_DELETE", targetType = "NAS_REGISTRATION", targetIdExpr = "#id")
    @Transactional
    public void delete(Long id) {
        requireAdmin();
        NasRegistration reg = requireRegistration(id);
        registrationRepository.delete(reg);
        log.info("[NAS-Reg] 记录已删除（不删 TrueNAS 用户）: id={}, username={}, status={}",
                id, reg.getUsername(), reg.getStatus());
    }

    // ==================== 内部辅助 ====================

    /** 审批/重申上游开通成功后异步通知 NAS 账号邮箱（失败仅记日志，不影响审批事务） */
    private void notifyActivated(NasRegistration reg) {
        try {
            emailService.sendNasActivated(reg.getEmail(), reg.getUsername(), reg.getFullName(),
                    nasProperties.getTruenas().getBaseUrl());
        } catch (Exception e) {
            log.warn("[NAS-Reg] 激活通知触发失败(不影响审批): id={}, err={}", reg.getId(), e.getMessage());
        }
    }

    /** 提交注册申请后异步通知申请者邮箱（已收到待审批，失败仅记日志） */
    private void notifySubmitted(NasRegistration reg) {
        try {
            emailService.sendNasSubmitted(reg.getEmail(), reg.getUsername(), reg.getFullName());
        } catch (Exception e) {
            log.warn("[NAS-Reg] 提交通知触发失败(不影响提交): id={}, err={}", reg.getId(), e.getMessage());
        }
    }

    /** 拒绝后异步通知申请者邮箱（含原因，失败仅记日志） */
    private void notifyRejected(NasRegistration reg, String reason) {
        try {
            emailService.sendNasRejected(reg.getEmail(), reg.getUsername(), reg.getFullName(), reason);
        } catch (Exception e) {
            log.warn("[NAS-Reg] 拒绝通知触发失败(不影响拒绝): id={}, err={}", reg.getId(), e.getMessage());
        }
    }

    /** TrueNAS 幂等查重 + 建用户，返回 {truenasUserId, truenasUid}；失败抛 TrueNas*Error */
    private Integer[] doProvision(String username, String fullName, String email, String password,
                                  String homeParent, Integer webuiGroupId) {
        Map<String, Object> existing = trueNasClient.userFindByUsername(username);
        if (existing != null) {
            log.info("[TrueNAS] 用户已存在，回填: username={}", username);
            return new Integer[]{toInt(existing.get("id")), toInt(existing.get("uid"))};
        }
        List<Integer> groups = (webuiGroupId != null) ? List.of(webuiGroupId) : null;
        Map<String, Object> created = trueNasClient.userCreate(username, fullName, password, email, homeParent, groups);
        log.info("[TrueNAS] 用户已创建: username={}", username);
        return new Integer[]{toInt(created.get("id")), toInt(created.get("uid"))};
    }

    private void validateFields(String username, String fullName, String email, String phone, String password) {
        if (username == null || !USERNAME_RE.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "username 必须以字母开头，仅含字母、数字、下划线、点、连字符，不超过 32 个字符");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "姓名必填");
        }
        if (fullName.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "姓名过长");
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
        Optional<NasInvitation> opt = invitationRepository.findByToken(token);
        if (opt.isEmpty()) {
            return new BusinessException(ErrorCode.NAS_INVITATION_INVALID);
        }
        NasInvitation inv = opt.get();
        if (inv.getRevokedAt() != null) {
            return new BusinessException(ErrorCode.NAS_INVITATION_REVOKED);
        }
        if (inv.isExpired()) {
            return new BusinessException(ErrorCode.NAS_INVITATION_EXPIRED);
        }
        if (inv.getUsedCount() >= inv.getMaxUses()) {
            return new BusinessException(ErrorCode.NAS_INVITATION_EXHAUSTED);
        }
        return new BusinessException(ErrorCode.NAS_INVITATION_EXHAUSTED);
    }

    private NasRegistration requireRegistration(Long id) {
        return registrationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NAS_REGISTRATION_NOT_FOUND));
    }

    private NasRegistrationListItem toListItem(NasRegistration reg) {
        return new NasRegistrationListItem(
                reg.getId(), reg.getInvitationId(), reg.getUsername(), reg.getFullName(),
                reg.getEmail(), reg.getPhone(), reg.getStatus(), reg.getTruenasUserId(),
                reg.getTruenasUid(), reg.getSubmittedAt(), reg.getReviewedBy(), reg.getReviewedAt(),
                reg.getRejectReason(), reg.getProvisionError(), reg.getPasswordEnc() != null
        );
    }

    private NasRegistrationApproveResponse buildApproveResponse(NasRegistration reg, Integer webuiGroupId, String message) {
        return new NasRegistrationApproveResponse(
                message, reg.getId(), reg.getStatus(), reg.getTruenasUserId(), reg.getTruenasUid(),
                nasProperties.getTruenas().getBaseUrl(),
                webuiGroupId != null ? ROLE_FOR_GROUP.get(webuiGroupId) : null);
    }

    private static Map<String, Object> errorState(String message) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("available", false);
        state.put("error", message);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> extractGroups(Object groupsObj) {
        if (groupsObj instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof Number)
                    .map(o -> ((Number) o).intValue())
                    .toList();
        }
        return List.of();
    }

    private static Integer toInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : null;
    }

    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }
}
