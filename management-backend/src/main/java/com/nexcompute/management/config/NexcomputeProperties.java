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
    private Cors cors = new Cors();

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
    }

    @Data
    public static class Storage {
        private String root = "./data/storage";
        private String imageTarDir;
        private String publicImageDir;
        private String migrationStagingDir;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }
}
