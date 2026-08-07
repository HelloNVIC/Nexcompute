package com.nexcompute.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * NewAPI 用户分配模块配置（newapi-user-allocation D4）。
 * 绑定 application.yml 的 nexcompute.newapi.*（docker-compose environment: 注入 NEWAPI_*）。
 * 无注入时回退默认值，应用仍可启动（NewAPI ping 失败仅 WARN 不阻断，D10）。
 * <p>注意：AES-GCM 密码加密密钥不在本类，复用 {@link NasProperties#getPasswordEncKey()}
 * 经 {@code NasPasswordEncryptor} 加解密（NAS/NewAPI 共用同一密钥与密文格式，见 D7）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "nexcompute.newapi")
public class NewApiProperties {

    /** NewAPI REST 基址（如 http://10.13.66.18:3001） */
    private String baseUrl = "http://10.13.66.18:3001";
    /** 系统访问令牌（Authorization: Bearer）；轮换后须更新 compose 并重启 */
    private String accessToken = "";
    /** 调用方用户 id（New-Api-User 头，须属管理员角色，否则建用户被拒） */
    private String apiUser = "";
    /** 单请求超时（秒） */
    private int timeoutSeconds = 30;
    /** 连接错误重试次数 */
    private int retries = 2;
    /** PENDING 注册超期天数（超过后扫描擦密码并置 REJECTED） */
    private int pendingExpireDays = 7;
    /** PENDING 超期扫描间隔（小时） */
    private int expiryScanIntervalHours = 6;
    /** 公开注册页基址（拼 {portalBaseUrl}/newapi-register?token= 注册链接） */
    private String portalBaseUrl = "http://localhost:5173";
}
