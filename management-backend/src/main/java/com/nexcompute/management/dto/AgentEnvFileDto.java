package com.nexcompute.management.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 受控端环境文件清单项（D4）。
 * 受控端按 md5 增量同步，path 为管理端文件传输下载用 sourcePath。
 */
@Data
@AllArgsConstructor
public class AgentEnvFileDto {
    private String filename;
    private String md5;
    private long size;
    /** 管理端文件源路径（/api/agent/file/download/init 的 sourcePath） */
    private String path;
}
