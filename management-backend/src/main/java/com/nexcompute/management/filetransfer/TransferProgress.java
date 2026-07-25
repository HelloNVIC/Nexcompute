package com.nexcompute.management.filetransfer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件传输进度（任务 7.1、7.6）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferProgress {

    /** 传输 ID */
    private String transferId;

    /** 方向：upload（受控端->管理端）/ download（管理端->受控端） */
    private String direction;

    /** 文件名/路径 */
    private String fileName;

    /** 下载方向的源文件全路径（platform-refinements #5：readChunk 直接用，避免子目录路径重建错误） */
    private String sourcePath;

    /** 总块数 */
    private int totalChunks;

    /** 已完成块数 */
    private int doneChunks;

    /** 总字节数 */
    private long totalBytes;

    /** 已传输字节数 */
    private long doneBytes;

    /** 状态：pending / transferring / completed / failed / checksum_failed */
    private String status;

    /** 整体校验和（SHA-256） */
    private String checksum;

    /** 错误信息 */
    private String error;

    /** 关联的实例编号 */
    private String instanceNumber;

    /** 传输类型：migration / image / public-image */
    private String type;

    private long createdAt;
    private long updatedAt;
}
