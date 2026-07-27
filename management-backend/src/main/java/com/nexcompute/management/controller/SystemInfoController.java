package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.EmailTrigger;
import com.nexcompute.management.domain.SystemConfig;
import com.nexcompute.management.domain.SystemInfo;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.SystemConfigRepository;
import com.nexcompute.management.repository.SystemInfoRepository;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.EmailService;
import com.nexcompute.management.service.EmailTriggerService;
import com.nexcompute.management.config.NexcomputeProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统信息（platform-refinements #5）：管理员可设置，其他用户只读。
 * <p>email-notification D4/D5/D9/D10：SMTP/触发开关/品牌/Logo/测试发送 子路径（/system-info/email/**）。
 */
@Slf4j
@RestController
@RequestMapping("/system-info")
@RequiredArgsConstructor
public class SystemInfoController {

    private static final String PASSWD_MASK = "****";
    private static final long MAX_LOGO_BYTES = 1L * 1024 * 1024; // D9：Logo ≤1MB

    private final SystemInfoRepository repository;
    private final SystemConfigRepository systemConfigRepository;
    private final EmailTriggerService emailTriggerService;
    private final EmailService emailService;
    private final NexcomputeProperties properties;

    @GetMapping
    public ApiResponse<SystemInfo> get() {
        return ApiResponse.success(repository.findSingleton()
                .orElseGet(() -> SystemInfo.builder().id((short) 1).build()));
    }

    @PutMapping
    public ApiResponse<SystemInfo> update(@RequestBody SystemInfoRequest request) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可设置系统信息");
        }
        SystemInfo info = repository.findSingleton()
                .orElseGet(() -> SystemInfo.builder().id((short) 1).build());
        info.setMaintainer(request.getMaintainer());
        info.setMaintainerPhone(request.getMaintainerPhone());
        info.setOwner(request.getOwner());
        info.setOwnerPhone(request.getOwnerPhone());
        return ApiResponse.success(repository.save(info));
    }

    @Data
    public static class SystemInfoRequest {
        private String maintainer;
        private String maintainerPhone;
        private String owner;
        private String ownerPhone;
    }

    // ===== email-notification：SMTP 配置（D4/D5） =====

    /** GET /system-info/email：SMTP 配置（PASSWD 脱敏） */
    @GetMapping("/email")
    public ApiResponse<SmtpConfig> getEmailConfig() {
        SmtpConfig cfg = new SmtpConfig();
        cfg.setFrom(readConfig("email.smtp.from", properties.getEmail().getFrom()));
        cfg.setHost(readConfig("email.smtp.host", properties.getEmail().getHost()));
        cfg.setPort(readConfig("email.smtp.port", String.valueOf(properties.getEmail().getPort())));
        cfg.setProtocol(readConfig("email.smtp.protocol", properties.getEmail().getProtocol()));
        cfg.setUser(readConfig("email.smtp.user", properties.getEmail().getUser()));
        // PASSWD 脱敏：已配置返回 ****，未配置返回空
        String passwd = readConfig("email.smtp.passwd", properties.getEmail().getPasswd());
        cfg.setPasswd((passwd == null || passwd.isBlank()) ? "" : PASSWD_MASK);
        return ApiResponse.success(cfg);
    }

    /** PUT /system-info/email：ADMIN-only，明文写入 system_config，刷新 sender */
    @PutMapping("/email")
    public ApiResponse<SmtpConfig> updateEmailConfig(@RequestBody SmtpConfig request) {
        requireAdmin();
        upsertConfig("email.smtp.from", request.getFrom(), "SMTP 发件人地址");
        upsertConfig("email.smtp.host", request.getHost(), "SMTP 服务器地址");
        upsertConfig("email.smtp.port", request.getPort(), "SMTP 端口");
        upsertConfig("email.smtp.protocol", request.getProtocol(), "SMTP 协议（smtps/smtp）");
        upsertConfig("email.smtp.user", request.getUser(), "SMTP 认证用户名");
        // PASSWD：传入 **** 或空表示不变更，其余明文写入
        if (request.getPasswd() != null && !PASSWD_MASK.equals(request.getPasswd()) && !request.getPasswd().isBlank()) {
            upsertConfig("email.smtp.passwd", request.getPasswd(), "SMTP 认证密码（明文存储）");
        }
        emailService.refresh();
        log.info("[Email] SMTP 配置已更新 by user={}", SecurityUtils.getCurrentUserId());
        return getEmailConfig();
    }

    @Data
    public static class SmtpConfig {
        private String from;
        private String host;
        private String port;
        private String protocol;
        private String user;
        private String passwd;
    }

    // ===== email-notification：触发键全局开关（D3） =====

    @GetMapping("/email/triggers")
    public ApiResponse<Map<String, Object>> getTriggers() {
        Map<String, Object> result = new LinkedHashMap<>();
        emailTriggerService.allGlobalSwitches().forEach((t, enabled) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("triggerKey", t.name());
            item.put("description", t.getDescription());
            item.put("mandatory", t.isMandatory());
            item.put("enabled", enabled);
            result.put(t.name(), item);
        });
        return ApiResponse.success(result);
    }

    @PutMapping("/email/triggers")
    public ApiResponse<Map<String, Object>> updateTriggers(@RequestBody Map<String, Boolean> request) {
        requireAdmin();
        for (Map.Entry<String, Boolean> e : request.entrySet()) {
            try {
                EmailTrigger t = EmailTrigger.valueOf(e.getKey());
                emailTriggerService.setGlobalEnabled(t, Boolean.TRUE.equals(e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未知触发键: " + e.getKey());
            }
        }
        return getTriggers();
    }

    // ===== email-notification：品牌名与落款（D10） =====

    @GetMapping("/email/brand")
    public ApiResponse<BrandConfig> getBrand() {
        BrandConfig cfg = new BrandConfig();
        cfg.setName(readConfig("email.brand.name", properties.getEmail().getBrand().getName()));
        cfg.setSignature(readConfig("email.signature", properties.getEmail().getBrand().getSignature()));
        return ApiResponse.success(cfg);
    }

    @PutMapping("/email/brand")
    public ApiResponse<BrandConfig> updateBrand(@RequestBody BrandConfig request) {
        requireAdmin();
        if (request.getName() != null && !request.getName().isBlank()) {
            upsertConfig("email.brand.name", request.getName(), "邮件品牌名");
        }
        if (request.getSignature() != null) {
            upsertConfig("email.signature", request.getSignature(), "邮件落款（多行文本）");
        }
        log.info("[Email] 品牌配置已更新 by user={}", SecurityUtils.getCurrentUserId());
        return getBrand();
    }

    @Data
    public static class BrandConfig {
        private String name;
        private String signature;
    }

    // ===== email-notification：Logo 上传/预览（D9） =====

    /** GET /system-info/email/logo：预览当前 Logo（已上传优先，否则 classpath 默认 PNG），以 data URL 返回 */
    @GetMapping("/email/logo")
    public ApiResponse<LogoView> getLogo() {
        LogoView view = new LogoView();
        String filename = readConfig("email.brand.logo_filename", properties.getEmail().getBrand().getLogoFilename());
        view.setFilename(filename);
        try {
            Path custom = (filename != null && !filename.isBlank())
                    ? Paths.get(properties.getStorage().getRoot(), "email", filename) : null;
            byte[] data;
            String contentType;
            if (custom != null && Files.exists(custom)) {
                data = Files.readAllBytes(custom);
                contentType = Files.probeContentType(custom);
            } else {
                ClassPathResource def = new ClassPathResource("email/logo.png");
                data = def.getContentAsByteArray();
                contentType = "image/png";
            }
            if (contentType == null) contentType = "image/png";
            view.setContentType(contentType);
            view.setDataUrl("data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(data));
        } catch (IOException e) {
            log.warn("[Email] 读取 Logo 失败: {}", e.getMessage());
        }
        return ApiResponse.success(view);
    }

    /** POST /system-info/email/logo：上传 Logo（PNG/JPG，≤1MB，ADMIN-only），存 ${storage.root}/email/ */
    @PostMapping("/email/logo")
    public ApiResponse<LogoView> uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        requireAdmin();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Logo 文件为空");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Logo 文件不得超过 1MB");
        }
        String orig = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String ext;
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (orig.endsWith(".jpg") || orig.endsWith(".jpeg") || contentType.contains("jpeg")) {
            ext = "jpg";
        } else if (orig.endsWith(".png") || contentType.contains("png")) {
            ext = "png";
        } else {
            // 拒绝 SVG 与其他格式（D9）
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Logo 仅支持 PNG/JPG 格式（不支持 SVG）");
        }
        Path dir = Paths.get(properties.getStorage().getRoot(), "email");
        Files.createDirectories(dir);
        String filename = "logo." + ext;
        Path target = dir.resolve(filename);
        file.transferTo(target.toFile());
        upsertConfig("email.brand.logo_filename", filename, "邮件 Logo 文件名");
        emailService.refresh();
        log.info("[Email] Logo 已上传: {} by user={}", filename, SecurityUtils.getCurrentUserId());
        return getLogo();
    }

    @Data
    public static class LogoView {
        private String filename;
        private String contentType;
        private String dataUrl;
    }

    // ===== email-notification：测试发送（D4） =====

    @PostMapping("/email/test")
    public ApiResponse<Map<String, String>> sendTest(@RequestBody TestSendRequest request) {
        requireAdmin();
        if (request.getToEmail() == null || request.getToEmail().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "收件邮箱不能为空");
        }
        String result = emailService.sendTest(request.getToEmail());
        return ApiResponse.success(Map.of("message", result));
    }

    @Data
    public static class TestSendRequest {
        private String toEmail;
    }

    // ===== helpers =====

    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }

    private String readConfig(String key, String def) {
        return systemConfigRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(def == null ? "" : def);
    }

    private void upsertConfig(String key, String value, String description) {
        SystemConfig cfg = systemConfigRepository.findById(key)
                .orElseGet(() -> SystemConfig.builder().configKey(key).description(description).build());
        cfg.setConfigValue(value);
        systemConfigRepository.save(cfg);
    }
}
