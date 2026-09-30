package com.nexcompute.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Nexcompute 系统配置项（任务 1.7）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "nexcompute")
public class NexcomputeProperties {

    private Jwt jwt = new Jwt();
    private Monitoring monitoring = new Monitoring();
    private Agent agent = new Agent();
    private Storage storage = new Storage();
    private Email email = new Email();
    private Cors cors = new Cors();
    private Registry registry = new Registry();

    /**
     * registry-image-distribution D1：内网私有镜像仓库。
     * push/pull 与 Registry v2 API 检查同端口同服务，单 url 配置项：
     * docker 引用前缀 = {url}/{name}:{tag}，v2 API 基址 = http://{url}/v2/...
     */
    @Data
    public static class Registry {
        /** 私有镜像仓库地址（host:port，如 10.13.66.25:5000），不带协议前缀 */
        private String url = "10.13.66.25:5000";
    }

    @Data
    public static class Jwt {
        private String secret;
        private long expirationMs = 86400000L;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Data
    public static class Monitoring {
        /** 监控采集粒度，默认 5 秒 */
        private int collectIntervalSeconds = 5;
        /** 监控历史保留天数，默认 30 日 */
        private int retentionDays = 30;
        /** 心跳超时阈值，默认 30 秒 */
        private int heartbeatTimeoutSeconds = 30;
        /** 离线检测扫描间隔（毫秒） */
        private long offlineScanIntervalMs = 10000L;
    }

    @Data
    public static class Agent {
        /** 受控端心跳间隔（秒） */
        private int heartbeatIntervalSeconds = 5;
        /** WS 重连基础退避（毫秒） */
        private long wsReconnectBaseMs = 2000L;
        /** WS 重连最大退避（毫秒） */
        private long wsReconnectMaxMs = 60000L;
        /** 文件分块大小（字节），默认 4MB */
        private int fileTransferChunkSize = 4 * 1024 * 1024;
        /** OTA 升级：发送 agent.upgrade 命令并等待受控端回包的 WS 超时（毫秒），默认 300s */
        private long upgradeCommandTimeoutMs = 300_000L;
        /** OTA 升级：发完命令后等待受控端重启并回传新版本的轮询超时（毫秒），默认 180s */
        private long upgradeVersionWaitTimeoutMs = 180_000L;
    }

    @Data
    public static class Storage {
        private String root = "./data/storage";
        private String imageTarDir;
        private String publicImageDir;
        private String migrationStagingDir;
        /** 受控端环境文件托管目录（D4：${root}/env） */
        private String envDir;
        /** 受控端 OTA 升级 exe 托管目录（D7：${root}/agent-upgrade） */
        private String agentUpgradeDir;
    }

    /**
     * email-notification D4：SMTP/品牌默认值（${ENV} 可覆盖）。
     * 首启经 Flyway V28 种子入 system_config；运行时 EmailService 以 DB 为准，本配置作默认/兜底。
     */
    @Data
    public static class Email {
        private String from = "cufel@cufe.edu.cn";
        private String host = "smtp.exmail.qq.com";
        private int port = 465;
        private String protocol = "smtps";
        private String user = "cufel@cufe.edu.cn";
        /** 明文存储（D5：用户明确不加密），GET 接口脱敏 */
        private String passwd = "";
        private Brand brand = new Brand();

        @Data
        public static class Brand {
            private String name = "合算 Nexcompute";
            /** 多行落款 */
            private String signature = "- Nexcompute 管理平台\n（此邮件由系统自动发送，请勿直接回复）";
            /** 空=用 classpath 默认 PNG；非空用 ${storage.root}/email/ 下已上传文件 */
            private String logoFilename = "";
        }
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }
}
