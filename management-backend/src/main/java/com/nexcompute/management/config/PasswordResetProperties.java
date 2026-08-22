package com.nexcompute.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 密码管理配置（password-management-and-id-validation D10）。
 * 绑定 application.yml 的 nexcompute.password-reset.*（docker-compose environment: 注入 PASSWORD_RESET_*）。
 * 无注入时回退默认值，应用仍可启动。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "nexcompute.password-reset")
public class PasswordResetProperties {

    /** 验证码有效期（分钟），默认 10 */
    private int codeExpireMinutes = 10;
    /** 发送限频冷却（秒）：同一用户名在该窗口内不重复建码，默认 60 */
    private int sendCooldownSeconds = 60;
    /** 每码校验失败次数上限：达上限后置 consumed_at 作废，默认 5 */
    private int maxAttempts = 5;
    /** 过期/已消费码清理扫描间隔（小时），默认 6 */
    private int cleanupIntervalHours = 6;
    /** 清理保留天数：删除 expires_at < now - retain-days 的行，默认 7 */
    private int cleanupRetainDays = 7;
}
