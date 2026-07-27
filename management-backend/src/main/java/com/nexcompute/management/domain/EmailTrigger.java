package com.nexcompute.management.domain;

import lombok.Getter;

/**
 * email-notification D2：8 邮件触发键 + mandatory 标志。
 * <p>mandatory=true 的触发键（用户注册/账户被禁用/账户已启用）为强制提醒，用户不可 opt-out，
 * 仅受管理员全局开关影响；其余 5 类学生/导师可逐项关闭。
 * <p>枚举名即 {@code system_config} 键 {@code email.trigger.<NAME>.enabled} 与 {@code user_email_pref.trigger_key} 的取值。
 */
@Getter
public enum EmailTrigger {

    USER_REGISTERED("用户注册", "用户注册", true),
    USER_DISABLED("禁用账户", "用户账户被禁用", true),
    USER_ENABLED("启用账户", "用户账户已启用", true),
    INSTANCE_ALLOCATED("分配实例", "用户被分配实例", false),
    INSTANCE_DEALLOCATED("撤销实例分配", "用户实例分配被撤销", false),
    STORAGE_POOL_MIGRATED("存储池迁移", "用户存储池迁移", false),
    IMAGE_PERMISSION_CHANGED("镜像权限变更", "用户镜像权限变化", false),
    CONTAINER_PERMISSION_CHANGED("容器权限变更", "用户容器权限变化", false);

    /** 操作日志中的操作类型中文 label（${actionLabel}） */
    private final String actionLabel;
    /** 触发键中文描述（前端开关表/用户偏好展示） */
    private final String description;
    /** true=强制提醒，用户不可 opt-out（仅全局开关生效） */
    private final boolean mandatory;

    EmailTrigger(String actionLabel, String description, boolean mandatory) {
        this.actionLabel = actionLabel;
        this.description = description;
        this.mandatory = mandatory;
    }

    /** system_config 全局开关键：email.trigger.&lt;ENUM&gt;.enabled */
    public String configKey() {
        return "email.trigger." + name() + ".enabled";
    }
}
