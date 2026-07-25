package com.nexcompute.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 容器连接信息（任务 10.5）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionInfo {

    /** SSH 连接信息 */
    private SshConnection ssh;

    /** 应用端口连接信息 */
    private List<AppConnection> apps;

    /** 连接模式：direct / tunnel */
    private String mode;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SshConnection {
        private String host;
        private int port;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppConnection {
        private String name;
        private int containerPort;
        private int hostPort;
        private String url;
    }
}
