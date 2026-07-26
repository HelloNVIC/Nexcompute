package com.nexcompute.management.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 管理端文件存储目录初始化（任务 1.6）
 *
 * 目录结构：
 *   {storage.root}/
 *   ├── images/            用户镜像 tar（任务 9.1）
 *   │   └── {userId}/
 *   ├── public-images/     公共镜像库（任务 9.7）
 *   ├── migration/         存储池迁移中转（任务 8.5）
 *   │   └── {transferId}/
 *   └── tmp/               临时文件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageInitializer {

    private final NexcomputeProperties properties;

    @PostConstruct
    public void init() throws IOException {
        NexcomputeProperties.Storage storage = properties.getStorage();

        String root = storage.getRoot();
        if (storage.getImageTarDir() == null || storage.getImageTarDir().isBlank()) {
            storage.setImageTarDir(root + "/images");
        }
        if (storage.getPublicImageDir() == null || storage.getPublicImageDir().isBlank()) {
            storage.setPublicImageDir(root + "/public-images");
        }
        if (storage.getMigrationStagingDir() == null || storage.getMigrationStagingDir().isBlank()) {
            storage.setMigrationStagingDir(root + "/migration");
        }
        if (storage.getEnvDir() == null || storage.getEnvDir().isBlank()) {
            storage.setEnvDir(root + "/env");
        }
        if (storage.getAgentUpgradeDir() == null || storage.getAgentUpgradeDir().isBlank()) {
            storage.setAgentUpgradeDir(root + "/agent-upgrade");
        }

        createDir(storage.getImageTarDir(), "镜像 tar");
        createDir(storage.getPublicImageDir(), "公共镜像库");
        createDir(storage.getMigrationStagingDir(), "迁移中转");
        createDir(storage.getEnvDir(), "受控端环境文件");
        createDir(storage.getAgentUpgradeDir(), "受控端 OTA 升级");
        createDir(root + "/tmp", "临时文件");

        log.info("[Storage] 文件存储根目录: {}", root);
        log.info("[Storage] 系统配置: 采集粒度={}s, 保留={}日, 心跳超时={}s, 心跳间隔={}s, 文件分块={}B",
                properties.getMonitoring().getCollectIntervalSeconds(),
                properties.getMonitoring().getRetentionDays(),
                properties.getMonitoring().getHeartbeatTimeoutSeconds(),
                properties.getAgent().getHeartbeatIntervalSeconds(),
                properties.getAgent().getFileTransferChunkSize());
    }

    private void createDir(String path, String desc) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            Files.createDirectories(p);
            log.info("[Storage] 创建目录 [{}]: {}", desc, p.toAbsolutePath());
        }
    }
}
