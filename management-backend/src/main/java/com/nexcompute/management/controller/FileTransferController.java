package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.filetransfer.FileTransferService;
import com.nexcompute.management.filetransfer.TransferProgress;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * 文件传输接口（任务 7.2、7.3、7.6）
 * 受控端调用，路径在 SecurityConfig 中 permitAll（使用 agentToken 校验）。
 */
@RestController
@RequestMapping("/agent/file")
@RequiredArgsConstructor
public class FileTransferController {

    private final FileTransferService transferService;

    // ========== 上传（受控端 -> 管理端，任务 7.2）==========

    @PostMapping("/upload/init")
    public ApiResponse<TransferProgress> initUpload(@RequestBody InitUploadRequest request) {
        return ApiResponse.success(transferService.initUpload(
                request.getTransferId(), request.getFileName(), request.getTotalBytes(),
                request.getChecksum(), request.getInstanceNumber(), request.getType()));
    }

    @PostMapping("/upload/chunk")
    public ApiResponse<TransferProgress> uploadChunk(
            @RequestParam String transferId,
            @RequestParam int chunkIndex,
            @RequestParam String chunkChecksum,
            @RequestParam("file") MultipartFile file) throws IOException {
        byte[] data = file.getBytes();
        return ApiResponse.success(transferService.receiveChunk(transferId, chunkIndex, data, chunkChecksum));
    }

    @GetMapping("/upload/status")
    public ApiResponse<Set<Integer>> uploadStatus(@RequestParam String transferId) {
        return ApiResponse.success(transferService.getCompletedChunks(transferId));
    }

    // ========== 下载（管理端 -> 受控端，任务 7.3）==========

    @PostMapping("/download/init")
    public ApiResponse<TransferProgress> initDownload(@RequestBody InitDownloadRequest request) {
        return ApiResponse.success(transferService.initDownload(
                request.getTransferId(), request.getSourcePath(),
                request.getInstanceNumber(), request.getType()));
    }

    @GetMapping("/download/chunk")
    public ApiResponse<FileTransferService.ChunkData> downloadChunk(
            @RequestParam String transferId,
            @RequestParam int chunkIndex) throws IOException {
        FileTransferService.ChunkData chunk = transferService.readChunk(transferId, chunkIndex);
        return ApiResponse.success(chunk);
    }

    @PostMapping("/download/ack")
    public ApiResponse<Void> ackChunk(@RequestParam String transferId, @RequestParam int chunkIndex) {
        transferService.markChunkDownloaded(transferId, chunkIndex);
        return ApiResponse.success();
    }

    // ========== 进度查询（任务 7.6）==========

    @GetMapping("/progress")
    public ApiResponse<Map<String, TransferProgress>> listProgress(
            @RequestParam(required = false) String instanceNumber) {
        return ApiResponse.success(transferService.listProgress(instanceNumber));
    }

    @GetMapping("/progress/{transferId}")
    public ApiResponse<TransferProgress> getProgress(@PathVariable String transferId) {
        return ApiResponse.success(transferService.getProgress(transferId));
    }

    @Data
    public static class InitUploadRequest {
        private String transferId;
        private String fileName;
        private long totalBytes;
        private String checksum;
        private String instanceNumber;
        private String type;
    }

    @Data
    public static class InitDownloadRequest {
        private String transferId;
        private String sourcePath;
        private String instanceNumber;
        private String type;
    }
}
