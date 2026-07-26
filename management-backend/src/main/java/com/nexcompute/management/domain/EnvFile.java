package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 受控端环境准备文件元数据（D4）。
 * 管理员上传环境文件（Docker Desktop Installer.exe / NVIDIA Toolkit deb 等），
 * 记录 MD5 供受控端按 MD5 增量同步至本地 Env 文件夹。
 */
@Entity
@Table(name = "env_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnvFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255, unique = true)
    private String filename;

    @Column(nullable = false, length = 64)
    private String md5;

    @Column(nullable = false)
    private Long size;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_by_name", length = 100)
    private String uploadedByName;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private Instant uploadedAt;
}
