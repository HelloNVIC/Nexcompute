package com.nexcompute.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * NAS 分配模块配置（nas-allocation D4）。
 * 绑定 application.yml 的 nexcompute.nas.*（.env 经 spring.config.import 注入 TRUENAS_* / PASSWORD_ENC_KEY / NAS_*）。
 * 无 .env 时回退默认值，应用仍可启动（TrueNAS ping 失败仅 WARN 不阻断，D10）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "nexcompute.nas")
public class NasProperties {

    private Truenas truenas = new Truenas();
    /** AES-GCM 密钥（base64-urlsafe，解码后须 16/24/32 字节）；空则注册提交会抛配置错误 */
    private String passwordEncKey;
    /** PENDING 注册超期天数（超过后扫描擦密码并置 REJECTED） */
    private int pendingExpireDays = 7;
    /** PENDING 超期扫描间隔（小时） */
    private int expiryScanIntervalHours = 6;
    /** 公开注册页基址（拼 {portalBaseUrl}/nas-register?token= 注册链接） */
    private String portalBaseUrl = "http://localhost:5173";

    @Data
    public static class Truenas {
        /** TrueNAS REST 基址（/api/v2.0 前缀） */
        private String baseUrl = "http://10.13.66.23";
        /** API key（须属 ACCOUNT_WRITE 角色，否则 user.create 在批准时被拒） */
        private String apiKey = "";
        /** 自签 TLS 关校验（http:// 无影响，https:// 必关） */
        private boolean verifyTls = false;
        /** 单请求超时（秒） */
        private int timeoutSeconds = 30;
        /** 连接错误重试次数 */
        private int retries = 2;
        /** 新用户 home 父目录（空=仅 SMB；设置后新用户开 SSH/Shell，home 建在此父目录下） */
        private String userHomeParent = "";
    }
}
