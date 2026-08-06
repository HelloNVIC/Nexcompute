package com.nexcompute.management.service;

import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.Announcement;
import com.nexcompute.management.domain.EmailLog;
import com.nexcompute.management.domain.EmailTrigger;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.repository.EmailLogRepository;
import com.nexcompute.management.repository.SystemConfigRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * email-notification D1/D4/D6/D9：邮件发送服务。
 * <ul>
 *   <li>{@link #sendAt} / {@link #sendAtBatch} 为业务触发点入口，{@code @Async} 经 emailTaskExecutor 异步发送，
 *       不阻断业务事务；发送结果写 {@code email_log}（失败仅记不抛）。</li>
 *   <li>SMTP 配置运行时读 {@code system_config}（{@code email.smtp.*}），构建并缓存 {@link JavaMailSenderImpl}，
 *       配置变更后经 {@link #refresh()} 刷新。</li>
 *   <li>Logo：{@code email.brand.logo_filename} 非空用 {@code ${storage.root}/email/} 下已上传文件，否则 classpath 默认 PNG；
 *       经 CID 内联附件 {@code cid:logo} 嵌入正文。</li>
 *   <li>品牌名/落款读 {@code system_config}（{@code email.brand.name}/{@code email.signature}）。</li>
 * </ul>
 * 注意：{@code @Async} 仅经代理生效；{@code sendAtBatch} 内部直接调 {@link #doSend}（同步逻辑），
 * 批量整体在自身异步线程内顺序发送，避免自调用绕过代理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailTriggerService emailTriggerService;
    private final EmailTemplateService emailTemplateService;
    private final EmailLogRepository emailLogRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final UserRepository userRepository;
    private final NexcomputeProperties properties;

    /** 缓存的 JavaMailSender（配置指纹变化或 refresh 后置空重建） */
    private volatile JavaMailSenderImpl cachedSender;
    private volatile String cachedFingerprint;

    @PostConstruct
    void init() {
        // 启动即校验并可构建 sender（best-effort，失败延迟到首次发送）
        try {
            getOrCreateSender();
        } catch (Exception e) {
            log.warn("[Email] 启动构建 sender 失败（将在首次发送时重试）: {}", e.getMessage());
        }
    }

    /** 配置变更后刷新（PUT SMTP/品牌/Logo 后调用） */
    public void refresh() {
        cachedSender = null;
        cachedFingerprint = null;
    }

    // ===== 业务触发点入口 =====

    /**
     * 单收件人异步发送（D1/D6）。
     *
     * @param trigger   触发键
     * @param recipient 收件人（已加载 User，含 email/role/realName）
     * @param ctx       上下文（operatorName/time 及触发键专属字段；异步线程无 SecurityContext，调用方须同步填好）
     */
    @Async("emailTaskExecutor")
    public void sendAt(EmailTrigger trigger, User recipient, Map<String, Object> ctx) {
        if (recipient == null || recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            log.debug("[Email] 跳过：收件人无邮箱 trigger={} recipientId={}", trigger, recipient == null ? null : recipient.getId());
            return;
        }
        try {
            if (!emailTriggerService.shouldSend(trigger, recipient.getId())) {
                return;
            }
            doSend(trigger, recipient, ctx == null ? Map.of() : ctx);
        } catch (Exception e) {
            log.error("[Email] sendAt 异常（不影响业务）: trigger={} recipient={} err={}",
                    trigger, recipient.getEmail(), e.getMessage());
        }
    }

    /**
     * 批量收件人异步发送（D7 镜像权限变化：被共享个人 ∪ 原可见用户，去重）。
     * 同一用户仅发一封；逐个判定与发送。
     */
    @Async("emailTaskExecutor")
    public void sendAtBatch(EmailTrigger trigger, List<User> recipients, Map<String, Object> ctx) {
        if (recipients == null || recipients.isEmpty()) return;
        Map<String, Object> baseCtx = ctx == null ? Map.of() : ctx;
        // 按 id 去重（保持顺序）
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (User r : recipients) {
            if (r == null || r.getId() == null) continue;
            if (!seen.add(r.getId())) continue;
            if (r.getEmail() == null || r.getEmail().isBlank()) continue;
            try {
                if (!emailTriggerService.shouldSend(trigger, r.getId())) continue;
                // 每个收件人的 ctx 可能不同（如 IMAGE_PERMISSION_CHANGED 的 relation），用 baseCtx 作底，调用方可预置
                doSend(trigger, r, baseCtx);
            } catch (Exception e) {
                log.error("[Email] sendAtBatch 单条异常（不影响其余）: trigger={} recipient={} err={}",
                        trigger, r.getEmail(), e.getMessage());
            }
        }
    }

    /**
     * 测试发送（管理员手动输入收件人）。同步执行以便管理员即时获知结果。
     * trigger_key 留空（测试发送）。返回发送结果描述。
     */
    public String sendTest(String toEmail) {
        if (toEmail == null || toEmail.isBlank()) {
            return "收件人为空";
        }
        String brandName = readBrandName();
        String signature = readSignature();
        String operatorName = resolveOperatorName();
        EmailTemplateService.RenderedMail mail = emailTemplateService.renderTest(toEmail, operatorName, brandName, signature);
        EmailLog logEntry = EmailLog.builder()
                .triggerKey(null)
                .recipientEmail(toEmail)
                .subject(mail.subject())
                .build();
        try {
            sendMime(toEmail, mail.subject(), mail.html(), mail.plain());
            logEntry.setStatus("SUCCESS");
            return "测试邮件已发送至 " + toEmail;
        } catch (Exception e) {
            logEntry.setStatus("FAILED");
            logEntry.setError(truncate(e.getMessage(), 1000));
            log.warn("[Email] 测试发送失败: to={} err={}", toEmail, e.getMessage());
            return "发送失败：" + e.getMessage();
        } finally {
            try {
                emailLogRepository.save(logEntry);
            } catch (Exception ex) {
                log.error("[Email] 测试发送写日志失败: {}", ex.getMessage());
            }
        }
    }

    /** 在请求线程同步解析操作人 realName（异步线程无 SecurityContext）；无登录上下文返回"系统"。 */
    public String resolveOperatorName() {
        try {
            Long uid = SecurityUtils.getCurrentUserId();
            return userRepository.findById(uid).map(User::getRealName).orElse("系统");
        } catch (Exception e) {
            return "系统";
        }
    }

    /**
     * NAS 账号开通通知(nas-allocation):审批/重申上游成功后异步发送至 NAS 账号邮箱,
     * 引导用户前往 TrueNAS 登录。不依赖 EmailTrigger/平台 User(NAS 用户无平台账号)。
     * 失败仅记 email_log,不影响审批事务。trigger_key=NAS_ACCOUNT_ACTIVATED。
     */
    @Async("emailTaskExecutor")
    public void sendNasActivated(String toEmail, String username, String fullName, String truenasLoginUrl) {
        String brandName = readBrandName();
        String signature = readSignature();
        String safeName = safeName(fullName);
        String loginUrl = safeStr(truenasLoginUrl);
        String subject = "你的 NAS 账号已开通 - " + brandName;
        String plain = brandName + " - NAS 账号已开通\n\n" + safeName + " 你好:\n\n"
                + "你的 TrueNAS 账号 " + username + " 已由管理员开通,现在可以登录使用。\n"
                + "登录地址:" + loginUrl + "\n\n" + signature;
        String html = "<!DOCTYPE html><html><body style=\"font-family:-apple-system,'Segoe UI',sans-serif;color:#333\">"
                + "<img src=\"cid:logo\" alt=\"logo\" style=\"height:40px;margin-bottom:16px\"/>"
                + "<h2 style=\"margin:0 0 12px\">NAS 账号已开通</h2>"
                + "<p>" + safeName + " 你好:</p>"
                + "<p>你的 TrueNAS 账号 <b>" + username + "</b> 已由管理员开通,现在可以登录使用。</p>"
                + "<p>请前往以下地址登录:</p>"
                + "<p><a href=\"" + loginUrl + "\" style=\"color:#1677ff;font-size:16px\">" + loginUrl + "</a></p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:20px 0\"/>"
                + "<p style=\"color:#888;font-size:12px;white-space:pre-line\">" + signature + "</p>"
                + "</body></html>";
        sendNasMail("NAS_ACCOUNT_ACTIVATED", toEmail, subject, html, plain, "NAS 激活通知", username);
    }

    /**
     * NAS 注册申请提交通知:用户提交申请后异步发送至其邮箱,告知已收到、待审批。
     * trigger_key=NAS_REGISTRATION_SUBMITTED。
     */
    @Async("emailTaskExecutor")
    public void sendNasSubmitted(String toEmail, String username, String fullName) {
        String brandName = readBrandName();
        String signature = readSignature();
        String safeName = safeName(fullName);
        String subject = "NAS 注册申请已提交 - " + brandName;
        String plain = brandName + " - NAS 注册申请已提交\n\n" + safeName + " 你好:\n\n"
                + "你的 TrueNAS 账号 " + username + " 的注册申请已提交,管理员审批通过后将为你开通并再次通知你。\n\n"
                + signature;
        String html = "<!DOCTYPE html><html><body style=\"font-family:-apple-system,'Segoe UI',sans-serif;color:#333\">"
                + "<img src=\"cid:logo\" alt=\"logo\" style=\"height:40px;margin-bottom:16px\"/>"
                + "<h2 style=\"margin:0 0 12px\">注册申请已提交</h2>"
                + "<p>" + safeName + " 你好:</p>"
                + "<p>你的 TrueNAS 账号 <b>" + username + "</b> 的注册申请已提交,管理员审批通过后将为你开通并再次通知你。</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:20px 0\"/>"
                + "<p style=\"color:#888;font-size:12px;white-space:pre-line\">" + signature + "</p>"
                + "</body></html>";
        sendNasMail("NAS_REGISTRATION_SUBMITTED", toEmail, subject, html, plain, "NAS 提交确认", username);
    }

    /**
     * NAS 注册申请拒绝通知:管理员拒绝后异步发送至其邮箱,告知未通过及原因。
     * trigger_key=NAS_REGISTRATION_REJECTED。
     */
    @Async("emailTaskExecutor")
    public void sendNasRejected(String toEmail, String username, String fullName, String reason) {
        String brandName = readBrandName();
        String signature = readSignature();
        String safeName = safeName(fullName);
        String reasonText = (reason == null || reason.isBlank()) ? "（未提供具体原因）" : reason;
        String subject = "NAS 注册申请未通过 - " + brandName;
        String plain = brandName + " - NAS 注册申请未通过\n\n" + safeName + " 你好:\n\n"
                + "很抱歉,你的 TrueNAS 账号 " + username + " 的注册申请未通过审核。\n"
                + "原因:" + reasonText + "\n\n" + signature;
        String html = "<!DOCTYPE html><html><body style=\"font-family:-apple-system,'Segoe UI',sans-serif;color:#333\">"
                + "<img src=\"cid:logo\" alt=\"logo\" style=\"height:40px;margin-bottom:16px\"/>"
                + "<h2 style=\"margin:0 0 12px\">注册申请未通过</h2>"
                + "<p>" + safeName + " 你好:</p>"
                + "<p>很抱歉,你的 TrueNAS 账号 <b>" + username + "</b> 的注册申请未通过审核。</p>"
                + "<p>原因:" + reasonText + "</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:20px 0\"/>"
                + "<p style=\"color:#888;font-size:12px;white-space:pre-line\">" + signature + "</p>"
                + "</body></html>";
        sendNasMail("NAS_REGISTRATION_REJECTED", toEmail, subject, html, plain, "NAS 拒绝通知", username);
    }

    /** NAS 邮件发送公共 helper:异步线程内同步发送 + 写 email_log（失败仅记不影响业务） */
    private void sendNasMail(String triggerKey, String toEmail, String subject, String html, String plain,
                             String logTag, String username) {
        if (toEmail == null || toEmail.isBlank()) {
            log.debug("[Email] {} 跳过:无邮箱 username={}", logTag, username);
            return;
        }
        EmailLog logEntry = EmailLog.builder()
                .triggerKey(triggerKey)
                .recipientEmail(toEmail)
                .subject(subject)
                .build();
        try {
            sendMime(toEmail, subject, html, plain);
            logEntry.setStatus("SUCCESS");
            log.info("[Email] {} 已发送: to={} username={}", logTag, toEmail, username);
        } catch (Exception e) {
            logEntry.setStatus("FAILED");
            logEntry.setError(truncate(e.getMessage(), 1000));
            log.warn("[Email] {} 发送失败(不影响业务): to={} err={}", logTag, toEmail, e.getMessage());
        } finally {
            try {
                emailLogRepository.save(logEntry);
            } catch (Exception ex) {
                log.error("[Email] {} 写日志失败: {}", logTag, ex.getMessage());
            }
        }
    }

    private static String safeName(String fullName) {
        return (fullName == null || fullName.isBlank()) ? "用户" : fullName;
    }

    private static String safeStr(String s) {
        return (s == null || s.isBlank()) ? "" : s;
    }

    /** 公告未读名单邮件提醒（管理员手动触发，非事件触发键）。
     * 绕过用户偏好与全局开关（管理员广播），逐个异步发送并写 email_log（trigger_key=ANNOUNCEMENT_REMINDER）。
     * 返回成功发送数（失败仅记日志不抛）。
     */
    @Async("emailTaskExecutor")
    public void sendAnnouncementReminder(Announcement ann, List<User> recipients, String operatorName) {
        if (recipients == null || recipients.isEmpty()) return;
        String brandName = readBrandName();
        String signature = readSignature();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        int sent = 0;
        for (User r : recipients) {
            if (r == null || r.getId() == null || !seen.add(r.getId())) continue;
            if (r.getEmail() == null || r.getEmail().isBlank()) continue;
            EmailTemplateService.RenderedMail mail = emailTemplateService.renderAnnouncementReminder(
                    ann, r, operatorName, brandName, signature);
            EmailLog logEntry = EmailLog.builder()
                    .triggerKey("ANNOUNCEMENT_REMINDER")
                    .recipientUserId(r.getId())
                    .recipientEmail(r.getEmail())
                    .subject(mail.subject())
                    .build();
            try {
                sendMime(r.getEmail(), mail.subject(), mail.html(), mail.plain());
                logEntry.setStatus("SUCCESS");
                sent++;
            } catch (Exception e) {
                logEntry.setStatus("FAILED");
                logEntry.setError(truncate(e.getMessage(), 1000));
                log.warn("[Email] 公告提醒发送失败: ann={} to={} err={}", ann.getId(), r.getEmail(), e.getMessage());
            } finally {
                try {
                    emailLogRepository.save(logEntry);
                } catch (Exception ex) {
                    log.error("[Email] 公告提醒写日志失败: {}", ex.getMessage());
                }
            }
        }
        log.info("[Email] 公告未读提醒完成: ann={} 发送 {} 封", ann.getId(), sent);
    }

    // ===== 同步发送核心 =====

    private void doSend(EmailTrigger trigger, User recipient, Map<String, Object> ctx) {
        String brandName = readBrandName();
        String signature = readSignature();
        EmailTemplateService.RenderedMail mail = emailTemplateService.render(trigger, recipient, ctx, brandName, signature);
        EmailLog logEntry = EmailLog.builder()
                .triggerKey(trigger.name())
                .recipientUserId(recipient.getId())
                .recipientEmail(recipient.getEmail())
                .subject(mail.subject())
                .build();
        try {
            sendMime(recipient.getEmail(), mail.subject(), mail.html(), mail.plain());
            logEntry.setStatus("SUCCESS");
        } catch (Exception e) {
            logEntry.setStatus("FAILED");
            logEntry.setError(truncate(e.getMessage(), 1000));
            log.warn("[Email] 发送失败（业务不受影响）: trigger={} to={} err={}",
                    trigger, recipient.getEmail(), e.getMessage());
        } finally {
            try {
                emailLogRepository.save(logEntry);
            } catch (Exception ex) {
                log.error("[Email] 写 email_log 失败: {}", ex.getMessage());
            }
        }
    }

    private void sendMime(String to, String subject, String html, String plain) throws Exception {
        JavaMailSenderImpl sender = getOrCreateSender();
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom(readSmtp("email.smtp.from", properties.getEmail().getFrom()));
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(plain == null ? "" : plain, html);
        // Logo 经 CID 内联附件
        Resource logo = resolveLogo();
        helper.addInline("logo", logo, logoContentType(logo));
        sender.send(mime);
    }

    // ===== JavaMailSender 缓存 =====

    private JavaMailSenderImpl getOrCreateSender() {
        String host = readSmtp("email.smtp.host", properties.getEmail().getHost());
        String port = readSmtp("email.smtp.port", String.valueOf(properties.getEmail().getPort()));
        String protocol = readSmtp("email.smtp.protocol", properties.getEmail().getProtocol());
        String user = readSmtp("email.smtp.user", properties.getEmail().getUser());
        String passwd = readSmtp("email.smtp.passwd", properties.getEmail().getPasswd());
        String fingerprint = host + "|" + port + "|" + protocol + "|" + user + "|" + (passwd == null ? 0 : passwd.length());
        JavaMailSenderImpl sender = cachedSender;
        if (sender != null && fingerprint.equals(cachedFingerprint)) {
            return sender;
        }
        synchronized (this) {
            if (cachedSender != null && fingerprint.equals(cachedFingerprint)) {
                return cachedSender;
            }
            JavaMailSenderImpl s = new JavaMailSenderImpl();
            s.setHost(host);
            s.setPort(parseInt(port, properties.getEmail().getPort()));
            s.setUsername(user);
            s.setPassword(passwd);
            s.setDefaultEncoding("UTF-8");
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            boolean smtps = "smtps".equalsIgnoreCase(protocol);
            if (smtps) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.trust", "*");
                props.put("mail.smtp.starttls.enable", "false");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.ssl.enable", "false");
            }
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "30000");
            props.put("mail.smtp.writetimeout", "30000");
            s.setJavaMailProperties(props);
            cachedSender = s;
            cachedFingerprint = fingerprint;
            log.info("[Email] JavaMailSender 已构建: host={} port={} protocol={} user={}", host, port, protocol, user);
            return s;
        }
    }

    // ===== Logo 解析 =====

    private Resource resolveLogo() {
        String filename = readSmtp("email.brand.logo_filename", properties.getEmail().getBrand().getLogoFilename());
        if (filename != null && !filename.isBlank()) {
            Path p = Paths.get(properties.getStorage().getRoot(), "email", filename);
            if (Files.exists(p)) {
                return new FileSystemResource(p);
            }
            log.warn("[Email] 已配置 Logo 文件不存在，回退默认 PNG: {}", p);
        }
        return new ClassPathResource("email/logo.png");
    }

    private String logoContentType(Resource logo) {
        String name = "";
        try {
            File f = logo.getFile();
            name = f.getName().toLowerCase();
        } catch (Exception ignored) {
            // classpath resource 无文件句柄，按默认 PNG
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "image/png";
    }

    // ===== system_config 读取（默认值取 NexcomputeProperties） =====

    private String readSmtp(String key, String def) {
        return systemConfigRepository.findById(key)
                .map(c -> c.getConfigValue())
                .filter(v -> v != null && !v.isBlank())
                .orElse(def == null ? "" : def);
    }

    private String readBrandName() {
        return readSmtp("email.brand.name", properties.getEmail().getBrand().getName());
    }

    private String readSignature() {
        return readSmtp("email.signature", properties.getEmail().getBrand().getSignature());
    }

    // ===== 工具 =====

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
