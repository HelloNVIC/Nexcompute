package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.common.FileChecksums;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.EnvFile;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.dto.AgentEnvFileDto;
import com.nexcompute.management.repository.EnvFileRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 受控端环境文件托管服务（D4）。
 * 管理员上传环境准备所需文件（Docker Desktop Installer.exe / NVIDIA Toolkit deb 等），
 * 记录 MD5，受控端按 MD5 增量同步至本地 Env 文件夹。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvFileService {

    private final EnvFileRepository envFileRepository;
    private final UserRepository userRepository;
    private final NexcomputeProperties properties;

    /** 上传（同文件名覆盖：更新 md5/size 与磁盘文件） */
    @Transactional
    public EnvFile upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名为空");
        }
        String envDir = properties.getStorage().getEnvDir();
        try {
            Files.createDirectories(Paths.get(envDir));
            Path target = Paths.get(envDir, filename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String md5 = FileChecksums.md5File(target);
            long size = Files.size(target);

            Long uploaderId = SecurityUtils.getCurrentUserId();
            String uploaderName = userRepository.findById(uploaderId)
                    .map(User::getRealName).orElse(null);

            EnvFile envFile = envFileRepository.findByFilename(filename)
                    .orElseGet(() -> EnvFile.builder().filename(filename).build());
            envFile.setMd5(md5);
            envFile.setSize(size);
            envFile.setUploadedBy(uploaderId);
            envFile.setUploadedByName(uploaderName);
            return envFileRepository.save(envFile);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存环境文件失败: " + e.getMessage());
        }
    }

    public List<EnvFile> list() {
        return envFileRepository.findAll();
    }

    @Transactional
    public void delete(Long id) {
        EnvFile envFile = envFileRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "环境文件不存在"));
        Path target = Paths.get(properties.getStorage().getEnvDir(), envFile.getFilename());
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("[EnvFile] 删除磁盘文件失败: {} {}", target, e.getMessage());
        }
        envFileRepository.delete(envFile);
    }

    /** 受控端拉取环境文件清单（含下载用 sourcePath） */
    public List<AgentEnvFileDto> agentList() {
        String envDir = properties.getStorage().getEnvDir();
        return envFileRepository.findAll().stream()
                .map(f -> new AgentEnvFileDto(f.getFilename(), f.getMd5(), f.getSize(),
                        Paths.get(envDir, f.getFilename()).toString()))
                .toList();
    }
}
