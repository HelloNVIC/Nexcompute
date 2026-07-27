package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.EmailTrigger;
import com.nexcompute.management.domain.SystemConfig;
import com.nexcompute.management.domain.UserEmailPref;
import com.nexcompute.management.repository.SystemConfigRepository;
import com.nexcompute.management.repository.UserEmailPrefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * email-notification D3：邮件触发判定服务。
 * <ul>
 *   <li>全局开关：{@code system_config} 键 {@code email.trigger.<ENUM>.enabled}（默认全开）。</li>
 *   <li>用户偏好：{@code user_email_pref}（仅记主动关闭项；mandatory 触发键不入表、查询强制 true）。</li>
 *   <li>发送判定：{@code 全局开关 == true && (mandatory || 用户偏好 != false)}。</li>
 * </ul>
 * 邮件发送为低频异步操作，直接读库（无需缓存；与 {@code AuditSwitchService} 高频场景不同）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTriggerService {

    private static final boolean DEFAULT_GLOBAL_ENABLED = true;
    private static final boolean DEFAULT_USER_ENABLED = true;

    private final SystemConfigRepository systemConfigRepository;
    private final UserEmailPrefRepository userEmailPrefRepository;

    // ===== 全局开关 =====

    @Transactional(readOnly = true)
    public boolean isGlobalEnabled(EmailTrigger trigger) {
        try {
            return systemConfigRepository.findById(trigger.configKey())
                    .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                    .orElse(DEFAULT_GLOBAL_ENABLED);
        } catch (Exception e) {
            log.warn("[Email] 读取全局开关失败，按默认 {} 处理: trigger={} err={}",
                    DEFAULT_GLOBAL_ENABLED, trigger, e.getMessage());
            return DEFAULT_GLOBAL_ENABLED;
        }
    }

    @Transactional
    public boolean setGlobalEnabled(EmailTrigger trigger, boolean enabled) {
        SystemConfig cfg = systemConfigRepository.findById(trigger.configKey())
                .orElseGet(() -> SystemConfig.builder()
                        .configKey(trigger.configKey())
                        .description("邮件触发开关：" + trigger.getDescription())
                        .build());
        cfg.setConfigValue(Boolean.toString(enabled));
        systemConfigRepository.save(cfg);
        return enabled;
    }

    /** 全部 7 触发键全局开关（前端开关表） */
    @Transactional(readOnly = true)
    public Map<EmailTrigger, Boolean> allGlobalSwitches() {
        Map<EmailTrigger, Boolean> map = new LinkedHashMap<>();
        for (EmailTrigger t : EmailTrigger.values()) {
            map.put(t, isGlobalEnabled(t));
        }
        return map;
    }

    // ===== 用户偏好 =====

    /** mandatory 强制 true；无偏好记录默认 true */
    @Transactional(readOnly = true)
    public boolean isUserEnabled(EmailTrigger trigger, Long userId) {
        if (trigger.isMandatory()) {
            return true;
        }
        return userEmailPrefRepository.findByUserIdAndTriggerKey(userId, trigger.name())
                .map(UserEmailPref::getEnabled)
                .orElse(DEFAULT_USER_ENABLED);
    }

    /** 设置用户偏好（mandatory 不可关闭） */
    @Transactional
    public boolean setUserEnabled(EmailTrigger trigger, Long userId, boolean enabled) {
        if (trigger.isMandatory()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "该提醒为强制提醒，不可关闭：" + trigger.getDescription());
        }
        UserEmailPref pref = userEmailPrefRepository.findByUserIdAndTriggerKey(userId, trigger.name())
                .orElseGet(() -> UserEmailPref.builder()
                        .userId(userId)
                        .triggerKey(trigger.name())
                        .enabled(DEFAULT_USER_ENABLED)
                        .build());
        pref.setEnabled(enabled);
        userEmailPrefRepository.save(pref);
        return enabled;
    }

    // ===== 发送判定（D3）=====

    /**
     * 发送判定：全局开关 == true && (mandatory || 用户偏好 != false)。
     * mandatory 触发键不受用户偏好影响（强制发）；用户偏好缺失默认发。
     */
    @Transactional(readOnly = true)
    public boolean shouldSend(EmailTrigger trigger, Long userId) {
        if (!isGlobalEnabled(trigger)) {
            return false;
        }
        if (trigger.isMandatory()) {
            return true;
        }
        Boolean pref = userEmailPrefRepository.findByUserIdAndTriggerKey(userId, trigger.name())
                .map(UserEmailPref::getEnabled)
                .orElse(DEFAULT_USER_ENABLED);
        return pref == null || pref;
    }

    /** 用户偏好视图（含 mandatory 标志，前端 disabled 用） */
    @Transactional(readOnly = true)
    public List<PrefView> userPrefViews(Long userId) {
        List<UserEmailPref> prefs = userEmailPrefRepository.findByUserId(userId);
        Map<String, Boolean> prefMap = new LinkedHashMap<>();
        for (UserEmailPref p : prefs) {
            prefMap.put(p.getTriggerKey(), p.getEnabled());
        }
        List<PrefView> views = new java.util.ArrayList<>();
        for (EmailTrigger t : EmailTrigger.values()) {
            boolean enabled = t.isMandatory() || prefMap.getOrDefault(t.name(), DEFAULT_USER_ENABLED);
            views.add(new PrefView(t.name(), t.getDescription(), enabled, t.isMandatory()));
        }
        return views;
    }

    /** 用户偏好视图项 */
    public record PrefView(String triggerKey, String description, boolean enabled, boolean mandatory) {}
}
