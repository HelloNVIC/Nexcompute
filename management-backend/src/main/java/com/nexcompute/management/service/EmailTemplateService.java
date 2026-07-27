package com.nexcompute.management.service;

import com.nexcompute.management.domain.Announcement;
import com.nexcompute.management.domain.EmailTrigger;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * email-notification D1/D6/D7/D9/D10/D11：邮件模板渲染。
 * <p>按 {@code email-templates-draft.md} 草案实现 7 触发键主题/正文 + 通用 HTML 外壳
 * （Logo + 品牌标题 + 称呼 + 正文 + 操作日志 + 文字提醒 + 落款）。
 * <p>渲染为纯字符串拼装，不引入模板引擎依赖；用户提供的值在拼入 HTML 时统一转义防注入/破坏排版，
 * 纯文本 fallback 使用原始值。品牌名/落款/Logo 由 {@link EmailService} 发送前读 {@code system_config} 传入。
 */
@Service
public class EmailTemplateService {

    /** 邮件时间显示时区（平台面向 CUFE，统一东八区） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);

    /** 渲染产物：主题 + HTML 正文 + 纯文本 fallback */
    public record RenderedMail(String subject, String html, String plain) {}

    /** 单触发键渲染内容（不含外壳；字段均为原始值，HTML 转义在拼装时进行） */
    private record TriggerContent(String subject, String bodyHtml, String bodyPlain,
                                  String actionLabel, String targetLabel, String changeDetails,
                                  List<String> reminds, String operatorName, String time) {
        TriggerContent(String subject, String bodyHtml, String bodyPlain,
                       String actionLabel, String targetLabel, String changeDetails, List<String> reminds) {
            this(subject, bodyHtml, bodyPlain, actionLabel, targetLabel, changeDetails, reminds, "系统", "");
        }

        TriggerContent withOperatorTime(String op, String time) {
            return new TriggerContent(subject, bodyHtml, bodyPlain, actionLabel, targetLabel,
                    changeDetails, reminds, op, time);
        }
    }

    /**
     * 渲染邮件（外壳 + 触发键内容）。
     *
     * @param trigger   触发键
     * @param recipient 收件人（取 realName/role 派生称呼）
     * @param ctx       触发点上下文（operatorName/time 及触发键专属字段）
     * @param brandName 品牌名（system_config email.brand.name）
     * @param signature 落款（system_config email.signature，多行）
     */
    public RenderedMail render(EmailTrigger trigger, User recipient, Map<String, Object> ctx,
                               String brandName, String signature) {
        TriggerContent c = buildContent(trigger, recipient, ctx, brandName);
        String greeting = greeting(recipient);
        String html = shellHtml(brandName, greeting, c, signature);
        String plain = shellPlain(brandName, greeting, c, signature);
        return new RenderedMail(c.subject(), html, plain);
    }

    /**
     * 渲染测试邮件（管理员"测试发送"用，含完整外壳/Logo/品牌/落款/操作日志/提醒，
     * 便于管理员校验 SMTP 与品牌配置）。非真实触发键，trigger_key 在 email_log 留空。
     */
    public RenderedMail renderTest(String toEmail, String operatorName, String brandName, String signature) {
        String time = TS.format(Instant.now());
        String bodyHtml = "<p>这是一封来自 " + esc(brandName) + " 的测试邮件，用于校验邮件配置是否正常。</p>"
                + "<p>若您能正常看到本邮件的 Logo、品牌标题、操作日志与落款，说明邮件通道工作正常。</p>";
        String bodyPlain = "这是一封来自 " + brandName + " 的测试邮件，用于校验邮件配置是否正常。\n"
                + "若您能正常看到本邮件的 Logo、品牌标题、操作日志与落款，说明邮件通道工作正常。";
        List<String> reminds = List.of("本邮件由管理员手动触发，请勿直接回复。");
        TriggerContent c = new TriggerContent("【测试邮件】" + brandName + " 邮件配置测试",
                bodyHtml, bodyPlain, "测试发送", toEmail, "收件人 " + toEmail, reminds)
                .withOperatorTime(operatorName, time);
        User recipient = User.builder()
                .username(toEmail).realName("用户").role(UserRole.STUDENT)
                .email(toEmail).status("ACTIVE").passwordHash("").build();
        String greeting = greeting(recipient);
        return new RenderedMail(c.subject(), shellHtml(brandName, greeting, c, signature),
                shellPlain(brandName, greeting, c, signature));
    }

    /**
     * 渲染公告未读提醒邮件（管理员手动对未读名单触发，非事件触发键，email_log trigger_key=ANNOUNCEMENT_REMINDER）。
     */
    public RenderedMail renderAnnouncementReminder(Announcement ann, User recipient,
                                                    String operatorName, String brandName, String signature) {
        String time = TS.format(Instant.now());
        String contentHtml = esc(ann.getContent() == null ? "" : ann.getContent()).replace("\n", "<br/>");
        String bodyHtml = "<p>您有一条公告尚未查看：<strong>" + esc(ann.getTitle()) + "</strong></p>"
                + "<div style=\"margin:12px 0;padding:12px 16px;background:#f5f7fa;border-radius:4px;\">"
                + contentHtml + "</div>"
                + "<p>请登录平台在“公告”中查看完整内容。</p>";
        String bodyPlain = "您有一条公告尚未查看：" + ann.getTitle() + "\n\n"
                + (ann.getContent() == null ? "" : ann.getContent()) + "\n\n请登录平台在“公告”中查看完整内容。";
        List<String> reminds = List.of("本邮件为管理员手动触发的未读提醒，请勿直接回复。");
        String changeDetails = "发布人 " + (ann.getAuthorName() == null ? "-" : ann.getAuthorName())
                + (ann.getPublishAt() == null ? "" : "；发布于 " + TS.format(ann.getPublishAt()));
        TriggerContent c = new TriggerContent("公告提醒：" + ann.getTitle(),
                bodyHtml, bodyPlain, "公告未读提醒", ann.getTitle(), changeDetails, reminds)
                .withOperatorTime(operatorName, time);
        String greeting = greeting(recipient);
        return new RenderedMail(c.subject(), shellHtml(brandName, greeting, c, signature),
                shellPlain(brandName, greeting, c, signature));
    }

    // ===== 通用外壳 =====

    private String shellHtml(String brandName, String greeting, TriggerContent c, String signature) {
        StringBuilder html = new StringBuilder(2048);
        html.append("<div style=\"font-family:Segoe UI,system-ui,-apple-system,PingFang SC,Microsoft YaHei,Arial,sans-serif;"
                + "max-width:640px;margin:0 auto;color:#1f1f1f;font-size:14px;line-height:1.7;\">");
        // Logo + 品牌标题
        html.append("<div style=\"margin-bottom:16px;\">")
            .append("<img src=\"cid:logo\" alt=\"").append(esc(brandName)).append("\" height=\"40\" style=\"display:block;\" />")
            .append("<div style=\"font-size:18px;font-weight:700;color:#0958d9;margin-top:8px;\">")
            .append(esc(brandName)).append("</div></div>");
        // 称呼
        html.append("<p>").append(esc(greeting)).append("，您好：</p>");
        // 正文（bodyHtml 已为安全 HTML 片段）
        html.append("<div style=\"margin:12px 0;\">").append(c.bodyHtml()).append("</div>");
        // 操作日志
        html.append(opLogHtml(c));
        // 文字提醒
        html.append(remindHtml(c.reminds()));
        // 落款
        html.append("<div style=\"margin-top:24px;color:#8c8c8c;white-space:pre-wrap;\">")
            .append(esc(signature)).append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private String opLogHtml(TriggerContent c) {
        StringBuilder sb = new StringBuilder(640);
        sb.append("<div style=\"margin:16px 0;padding:12px 16px;background:#f5f7fa;border-left:3px solid #1677ff;border-radius:4px;\">");
        sb.append("<strong style=\"color:#0958d9;\">操作日志</strong>");
        sb.append("<table style=\"width:100%;border-collapse:collapse;margin-top:8px;font-size:13px;\">");
        row(sb, "操作人", c.operatorName());
        row(sb, "操作时间", c.time());
        row(sb, "操作类型", c.actionLabel());
        row(sb, "操作对象", c.targetLabel());
        if (c.changeDetails() != null && !c.changeDetails().isBlank()) {
            row(sb, "变更详情", c.changeDetails());
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><td style=\"padding:4px 12px 4px 0;color:#8c8c8c;width:90px;vertical-align:top;\">")
          .append(esc(label)).append("</td><td style=\"padding:4px 0;vertical-align:top;\">")
          .append(value == null ? "" : esc(value)).append("</td></tr>");
    }

    private String remindHtml(List<String> reminds) {
        if (reminds == null || reminds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(256);
        sb.append("<div style=\"margin:16px 0;padding:12px 16px;background:#fffbe6;border-left:3px solid #faad14;border-radius:4px;\">");
        sb.append("<strong style=\"color:#d48806;\">提醒</strong><ul style=\"margin:8px 0 0 0;padding-left:20px;\">");
        for (String r : reminds) {
            sb.append("<li style=\"margin:4px 0;\">").append(esc(r)).append("</li>");
        }
        sb.append("</ul></div>");
        return sb.toString();
    }

    private String shellPlain(String brandName, String greeting, TriggerContent c, String signature) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("【").append(brandName).append("】\n\n");
        sb.append(greeting).append("，您好：\n\n");
        sb.append(stripHtml(c.bodyPlain())).append("\n\n");
        sb.append("-- 操作日志 --\n");
        sb.append("操作人：").append(c.operatorName()).append("\n");
        sb.append("操作时间：").append(c.time()).append("\n");
        sb.append("操作类型：").append(c.actionLabel()).append("\n");
        sb.append("操作对象：").append(c.targetLabel()).append("\n");
        if (c.changeDetails() != null && !c.changeDetails().isBlank()) {
            sb.append("变更详情：").append(c.changeDetails()).append("\n");
        }
        if (c.reminds() != null && !c.reminds().isEmpty()) {
            sb.append("\n-- 提醒 --\n");
            for (String r : c.reminds()) sb.append("- ").append(r).append("\n");
        }
        sb.append("\n").append(signature).append("\n");
        return sb.toString();
    }

    // ===== 触发键内容构建 =====

    private TriggerContent buildContent(EmailTrigger trigger, User recipient, Map<String, Object> ctx, String brandName) {
        String operatorName = str(ctx, "operatorName", "系统");
        String time = formatTime(ctx);
        String realName = recipient != null && recipient.getRealName() != null ? recipient.getRealName() : "";
        String username = recipient != null && recipient.getUsername() != null ? recipient.getUsername() : "";
        return switch (trigger) {
            case USER_REGISTERED -> userRegistered(ctx, operatorName, time, realName, username, brandName);
            case USER_DISABLED -> userDisabled(ctx, operatorName, time, realName, username);
            case USER_ENABLED -> userEnabled(ctx, operatorName, time, realName, username, brandName);
            case INSTANCE_ALLOCATED -> instanceAllocated(ctx, operatorName, time, realName);
            case INSTANCE_DEALLOCATED -> instanceDeallocated(ctx, operatorName, time, realName);
            case STORAGE_POOL_MIGRATED -> storagePoolMigrated(ctx, operatorName, time, realName);
            case IMAGE_PERMISSION_CHANGED -> imagePermissionChanged(ctx, operatorName, time, realName);
            case CONTAINER_PERMISSION_CHANGED -> containerPermissionChanged(ctx, operatorName, time, realName);
        };
    }

    private TriggerContent userRegistered(Map<String, Object> ctx, String op, String time,
                                          String realName, String username, String brandName) {
        String groupName = str(ctx, "groupName", "");
        String mentorName = str(ctx, "mentorName", "");
        String subject = "欢迎注册 " + brandName;
        StringBuilder body = new StringBuilder();
        body.append("<p>您的账号已注册成功，欢迎使用 ").append(esc(brandName)).append("。</p>");
        body.append("<p>您已被加入课题组「<strong>").append(esc(groupName)).append("</strong>」，"
                + "后续可登录平台使用计算实例、存储池与镜像等资源。请妥善保管账号，首次登录后建议完善个人信息。</p>");
        if (!mentorName.isBlank()) {
            body.append("<p>您的指导老师：").append(esc(mentorName)).append("。</p>");
        }
        String bodyPlain = "您的账号已注册成功，欢迎使用 " + brandName + "。\n您已被加入课题组「" + groupName + "」"
                + (mentorName.isBlank() ? "" : "，指导老师：" + mentorName)
                + "。后续可登录平台使用计算实例、存储池与镜像等资源。";
        List<String> reminds = List.of(
                "首次登录后请及时完善个人信息（邮箱/手机号）。",
                "请妥善保管账号密码，勿与他人共享。");
        return new TriggerContent(subject, body.toString(), bodyPlain,
                "用户注册", realName + "（" + username + "）",
                "加入课题组 " + groupName, reminds).withOperatorTime(op, time);
    }

    private TriggerContent userDisabled(Map<String, Object> ctx, String op, String time,
                                        String realName, String username) {
        String reason = str(ctx, "reason", "");
        String subject = "您的账户已被禁用";
        String body = "<p>您的账号已被管理员禁用，即日起将无法登录平台。</p>"
                + "<p>如需了解原因或申请恢复，请联系系统管理员。</p>";
        String bodyPlain = "您的账号已被管理员禁用，即日起将无法登录平台。\n如需了解原因或申请恢复，请联系系统管理员。";
        List<String> reminds = List.of(
                "账号已无法登录，相关进行中的任务请提前与课题组沟通处理。",
                "如需恢复，请联系系统管理员。");
        String changeDetails = reason.isBlank() ? "" : "原因：" + reason;
        return new TriggerContent(subject, body, bodyPlain,
                "禁用账户", realName + "（" + username + "）",
                changeDetails, reminds).withOperatorTime(op, time);
    }

    private TriggerContent userEnabled(Map<String, Object> ctx, String op, String time,
                                       String realName, String username, String brandName) {
        String subject = "您的账户已启用";
        String body = "<p>您的账号已被管理员启用，即日起可登录 " + esc(brandName) + "。</p>"
                + "<p>如登录遇到问题，请联系系统管理员。</p>";
        String bodyPlain = "您的账号已被管理员启用，即日起可登录 " + brandName + "。\n如登录遇到问题，请联系系统管理员。";
        List<String> reminds = List.of(
                "请妥善保管账号密码，勿与他人共享。",
                "首次登录后建议完善个人信息（邮箱/手机号）。",
                "如登录异常，请联系系统管理员。");
        return new TriggerContent(subject, body, bodyPlain,
                "启用账户", realName + "（" + username + "）",
                "", reminds).withOperatorTime(op, time);
    }

    private TriggerContent instanceAllocated(Map<String, Object> ctx, String op, String time, String realName) {
        String instanceName = str(ctx, "instanceName", "");
        String instanceNumber = str(ctx, "instanceNumber", "");
        String subject = "实例「" + instanceName + "」已分配给您";
        String body = "<p>一台物理实例已分配给您使用。</p>"
                + "<p>实例「<strong>" + esc(instanceName) + "</strong>」（编号 " + esc(instanceNumber)
                + "）现已可用，您可登录平台在“物理实例”中查看其状态并部署容器。</p>";
        String bodyPlain = "一台物理实例已分配给您使用。\n实例「" + instanceName + "」（编号 " + instanceNumber
                + "）现已可用，您可登录平台在“物理实例”中查看其状态并部署容器。";
        List<String> reminds = List.of(
                "请勿在实例上存放未备份的重要数据，平台不保证单实例数据持久。",
                "使用完毕请及时释放资源，避免占用。");
        return new TriggerContent(subject, body, bodyPlain,
                "分配实例", instanceName + "（编号 " + instanceNumber + "）",
                "分配给 " + realName, reminds).withOperatorTime(op, time);
    }

    private TriggerContent instanceDeallocated(Map<String, Object> ctx, String op, String time, String realName) {
        String instanceName = str(ctx, "instanceName", "");
        String instanceNumber = str(ctx, "instanceNumber", "");
        String subject = "实例「" + instanceName + "」的分配已撤销";
        String body = "<p>您此前使用的物理实例分配已被撤销。</p>"
                + "<p>实例「<strong>" + esc(instanceName) + "</strong>」（编号 " + esc(instanceNumber)
                + "）已不再分配给您。请提前备份该实例上的重要数据；如需继续使用，请联系系统管理员重新申请。</p>";
        String bodyPlain = "您此前使用的物理实例分配已被撤销。\n实例「" + instanceName + "」（编号 " + instanceNumber
                + "）已不再分配给您。请提前备份该实例上的重要数据；如需继续使用，请联系系统管理员重新申请。";
        List<String> reminds = List.of(
                "请立即备份实例上的重要数据，撤销后将无法访问。",
                "如需继续使用，请联系系统管理员重新申请。");
        return new TriggerContent(subject, body, bodyPlain,
                "撤销实例分配", instanceName + "（编号 " + instanceNumber + "）",
                "从 " + realName + " 撤销", reminds).withOperatorTime(op, time);
    }

    private TriggerContent storagePoolMigrated(Map<String, Object> ctx, String op, String time, String realName) {
        String poolName = str(ctx, "poolName", "");
        String sourceHost = str(ctx, "sourceHost", "");
        String targetHost = str(ctx, "targetHost", "");
        String status = str(ctx, "status", "迁移中");
        String subject = "存储池「" + poolName + "」迁移通知";
        String body = "<p>您的存储池已发起迁移。</p>"
                + "<p>存储池「<strong>" + esc(poolName) + "</strong>」正由「" + esc(sourceHost)
                + "」迁移至「" + esc(targetHost) + "」。迁移期间该存储池暂不可写，完成后将恢复访问。"
                + "请在迁移完成后确认数据完整性。</p>";
        String bodyPlain = "您的存储池已发起迁移。\n存储池「" + poolName + "」正由「" + sourceHost
                + "」迁移至「" + targetHost + "」。迁移期间该存储池暂不可写，完成后将恢复访问。请在迁移完成后确认数据完整性。";
        List<String> reminds = List.of(
                "迁移期间存储池暂不可写，请避免写入操作。",
                "迁移完成后请及时核对数据完整性。");
        return new TriggerContent(subject, body, bodyPlain,
                "存储池迁移", poolName,
                sourceHost + " -> " + targetHost + "（" + status + "）", reminds).withOperatorTime(op, time);
    }

    private TriggerContent imagePermissionChanged(Map<String, Object> ctx, String op, String time, String realName) {
        String imageName = str(ctx, "imageName", "");
        String relation = str(ctx, "relation", "SHARED_TO");
        String changeSummary = str(ctx, "changeSummary", "");
        String sharerName = str(ctx, "sharerName", "");
        String subject = "镜像「" + imageName + "」权限变更通知";
        StringBuilder body = new StringBuilder();
        StringBuilder bodyPlain = new StringBuilder();
        List<String> reminds;
        if ("ALREADY_VISIBLE".equals(relation)) {
            body.append("<p>您可见的镜像「<strong>").append(esc(imageName))
                .append("</strong>」的权限发生了变更。变更：").append(esc(changeSummary))
                .append("（由「").append(esc(op)).append("」操作）。如该变更影响您的使用，请及时关注。</p>");
            bodyPlain.append("您可见的镜像「").append(imageName).append("」的权限发生了变更。变更：")
                    .append(changeSummary).append("（由「").append(op).append("」操作）。如该变更影响您的使用，请及时关注。");
            reminds = List.of("若该变更撤销了您的访问，请及时联系共享人或管理员。");
        } else {
            body.append("<p>镜像「<strong>").append(esc(imageName)).append("</strong>」已由「")
                .append(esc(sharerName)).append("」共享给您，您现已可使用该镜像。"
                + "您可登录平台在“镜像”中查看并基于该镜像创建容器。</p>");
            bodyPlain.append("镜像「").append(imageName).append("」已由「").append(sharerName)
                    .append("」共享给您，您现已可使用该镜像。您可登录平台在“镜像”中查看并基于该镜像创建容器。");
            reminds = List.of("基于该镜像创建的容器数据请自行备份。");
        }
        String changeDetails = changeSummary + (sharerName.isBlank() ? "" : "；共享人 " + sharerName);
        return new TriggerContent(subject, body.toString(), bodyPlain.toString(),
                "镜像权限变更", imageName, changeDetails, reminds).withOperatorTime(op, time);
    }

    private TriggerContent containerPermissionChanged(Map<String, Object> ctx, String op, String time, String realName) {
        String containerName = str(ctx, "containerName", "");
        String changeType = str(ctx, "changeType", "SHARED");
        String sharerName = str(ctx, "sharerName", "");
        String expiresAt = str(ctx, "expiresAt", "");
        String subject = "容器「" + containerName + "」权限变更通知";
        StringBuilder body = new StringBuilder();
        StringBuilder bodyPlain = new StringBuilder();
        List<String> reminds;
        String actionLabel;
        if ("UNSHARED".equals(changeType)) {
            body.append("<p>您此前被共享的容器「<strong>").append(esc(containerName))
                .append("</strong>」已被取消共享。您将不再能访问该容器，请提前备份其中的重要数据。</p>");
            bodyPlain.append("您此前被共享的容器「").append(containerName)
                    .append("」已被取消共享。您将不再能访问该容器，请提前备份其中的重要数据。");
            reminds = List.of("请立即备份容器内重要数据，取消后无法访问。");
            actionLabel = "取消容器共享";
        } else {
            body.append("<p>容器「<strong>").append(esc(containerName)).append("</strong>」已由「")
                .append(esc(sharerName)).append("」共享给您。您可登录平台在“容器”中查看并进入该容器。");
            bodyPlain.append("容器「").append(containerName).append("」已由「").append(sharerName)
                    .append("」共享给您。您可登录平台在“容器”中查看并进入该容器。");
            if (!expiresAt.isBlank()) {
                body.append("本次共享将于 ").append(esc(expiresAt)).append(" 到期。");
                bodyPlain.append("本次共享将于 ").append(expiresAt).append(" 到期。");
            }
            body.append("</p>");
            reminds = List.of("共享容器内请勿存放隐私数据，所有者可随时取消共享。");
            actionLabel = "容器共享";
        }
        String changeDetails = "共享人 " + sharerName + (expiresAt.isBlank() ? "" : "，到期 " + expiresAt);
        return new TriggerContent(subject, body.toString(), bodyPlain.toString(),
                actionLabel, containerName, changeDetails, reminds).withOperatorTime(op, time);
    }

    // ===== helpers =====

    /** 称呼：STUDENT -> 同学，MENTOR/ADMIN -> 老师 */
    private String greeting(User recipient) {
        String name = recipient != null && recipient.getRealName() != null ? recipient.getRealName() : "用户";
        if (recipient != null && recipient.getRole() == UserRole.STUDENT) {
            return name + " 同学";
        }
        return name + " 老师";
    }

    private String formatTime(Map<String, Object> ctx) {
        Object t = ctx.get("time");
        if (t instanceof Instant inst) {
            return TS.format(inst);
        }
        if (t instanceof String s && !s.isBlank()) {
            return s;
        }
        return TS.format(Instant.now());
    }

    private String str(Map<String, Object> ctx, String key, String def) {
        Object v = ctx.get(key);
        if (v == null) return def;
        String s = v.toString();
        return s == null || s.isBlank() ? def : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 纯文本 fallback 中去除 HTML 标记 */
    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "");
    }
}
