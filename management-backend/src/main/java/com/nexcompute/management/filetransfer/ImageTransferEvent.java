package com.nexcompute.management.filetransfer;

/**
 * 文件传输完成事件（platform-refinements 6.4）。
 * 受控端上传 tar（type=image）完成或失败时发布，ImageService 监听以置镜像 READY/FAILED。
 */
public record ImageTransferEvent(String transferId, String type, String targetPath,
                                 long totalBytes, String checksum, boolean success) {
}
