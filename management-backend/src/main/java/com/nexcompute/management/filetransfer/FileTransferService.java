package com.nexcompute.management.filetransfer;

import com.nexcompute.management.config.NexcomputeProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文件传输服务（任务 7.1-7.3、7.6）
 *
 * 协议设计（任务 7.1）：
 * - 文件分块传输，默认块大小 4MB（可配置）
 * - 每块有索引（0-based），块附带 SHA-256 校验和
 * - 断点续传：传输状态持久化于 Redis，恢复时查询已完成块
 * - 文件整体校验和（SHA-256）比对
 *
 * 传输状态 Redis 键：
 *   nexcompute:transfer:{transferId} -> TransferProgress
 *   nexcompute:transfer:{transferId}:chunks -> Set<已完成的块索引>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileTransferService {

    private static final String PROGRESS_KEY_PREFIX = "nexcompute:transfer:";
    private static final String CHUNKS_KEY_SUFFIX = ":chunks";

    private final RedisTemplate<String, Object> redisTemplate;
    private final NexcomputeProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    // ========== 上传（受控端 -> 管理端）==========

    /**
     * 初始化上传传输（任务 7.2）
     */
    public TransferProgress initUpload(String transferId, String fileName, long totalBytes,
                                       String checksum, String instanceNumber, String type) {
        int chunkSize = properties.getAgent().getFileTransferChunkSize();
        int totalChunks = (int) ((totalBytes + chunkSize - 1) / chunkSize);

        TransferProgress progress = TransferProgress.builder()
                .transferId(transferId)
                .direction("upload")
                .fileName(fileName)
                .totalChunks(totalChunks)
                .doneChunks(0)
                .totalBytes(totalBytes)
                .doneBytes(0)
                .status("transferring")
                .checksum(checksum)
                .instanceNumber(instanceNumber)
                .type(type)
                .createdAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .build();

        saveProgress(progress);
        // 创建目标文件（预分配空间）
        String targetPath = resolveTargetPath(transferId, fileName, type);
        ensureFileExists(targetPath, totalBytes);
        return progress;
    }

    /**
     * 接收一个块（任务 7.2）
     */
    public TransferProgress receiveChunk(String transferId, int chunkIndex, byte[] data, String chunkChecksum) {
        TransferProgress progress = getProgress(transferId);
        if (progress == null) {
            throw new IllegalStateException("传输不存在: " + transferId);
        }

        // 校验块校验和
        if (!verifyChunkChecksum(data, chunkChecksum)) {
            log.warn("[FileTransfer] 块 {} 校验失败: {}", chunkIndex, transferId);
            progress.setStatus("checksum_failed");
            saveProgress(progress);
            publishImageEvent(progress, false);
            return progress;
        }

        // 写入文件对应位置
        int chunkSize = properties.getAgent().getFileTransferChunkSize();
        long offset = (long) chunkIndex * chunkSize;
        String targetPath = resolveTargetPath(transferId, progress.getFileName(), progress.getType());
        writeChunk(targetPath, offset, data);

        // 更新进度
        Set<Integer> doneChunks = getDoneChunks(transferId);
        if (!doneChunks.contains(chunkIndex)) {
            doneChunks.add(chunkIndex);
            redisTemplate.opsForSet().add(PROGRESS_KEY_PREFIX + transferId + CHUNKS_KEY_SUFFIX, chunkIndex);
            progress.setDoneChunks(doneChunks.size());
            progress.setDoneBytes((long) doneChunks.size() * chunkSize);
            if (doneChunks.size() >= progress.getTotalChunks()) {
                progress.setStatus("completed");
                publishImageEvent(progress, true);
            }
            progress.setUpdatedAt(System.currentTimeMillis());
            saveProgress(progress);
        }

        return progress;
    }

    /**
     * 镜像 tar 上传完成/失败时发布事件（platform-refinements 6.4）。
     * ImageService 监听以置 READY/FAILED；仅 type=image（受控端 commit 回传）触发。
     */
    private void publishImageEvent(TransferProgress progress, boolean success) {
        if (!"image".equals(progress.getType())) return;
        String targetPath = resolveTargetPath(progress.getTransferId(), progress.getFileName(), progress.getType());
        eventPublisher.publishEvent(new ImageTransferEvent(
                progress.getTransferId(), progress.getType(), targetPath,
                progress.getTotalBytes(), progress.getChecksum(), success));
    }

    /**
     * 查询已完成块（断点续传，任务 7.2）
     */
    public Set<Integer> getCompletedChunks(String transferId) {
        return getDoneChunks(transferId);
    }

    // ========== 下载（管理端 -> 受控端）==========

    /**
     * 初始化下载传输（任务 7.3）
     */
    public TransferProgress initDownload(String transferId, String sourcePath, String instanceNumber, String type) {
        Path path = Paths.get(sourcePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("源文件不存在: " + sourcePath);
        }
        try {
            long totalBytes = Files.size(path);
            String checksum = sha256File(sourcePath);
            int chunkSize = properties.getAgent().getFileTransferChunkSize();
            int totalChunks = (int) ((totalBytes + chunkSize - 1) / chunkSize);

            TransferProgress progress = TransferProgress.builder()
                    .transferId(transferId)
                    .direction("download")
                    .fileName(path.getFileName().toString())
                    .sourcePath(sourcePath)
                    .totalChunks(totalChunks)
                    .doneChunks(0)
                    .totalBytes(totalBytes)
                    .doneBytes(0)
                    .status("transferring")
                    .checksum(checksum)
                    .instanceNumber(instanceNumber)
                    .type(type)
                    .createdAt(System.currentTimeMillis())
                    .updatedAt(System.currentTimeMillis())
                    .build();
            saveProgress(progress);
            return progress;
        } catch (IOException e) {
            throw new RuntimeException("初始化下载失败", e);
        }
    }

    /**
     * 读取一个块（任务 7.3）
     */
    public ChunkData readChunk(String transferId, int chunkIndex) throws IOException {
        TransferProgress progress = getProgress(transferId);
        if (progress == null) {
            throw new IllegalStateException("传输不存在: " + transferId);
        }
        // platform-refinements #5：直接用原始 sourcePath（子目录路径），不再按 base+fileName 重建
        String sourcePath = progress.getSourcePath() != null ? progress.getSourcePath() : resolveSourcePath(progress);
        int chunkSize = properties.getAgent().getFileTransferChunkSize();
        long offset = (long) chunkIndex * chunkSize;

        try (RandomAccessFile raf = new RandomAccessFile(sourcePath, "r")) {
            raf.seek(offset);
            int remaining = (int) Math.min(chunkSize, progress.getTotalBytes() - offset);
            byte[] data = new byte[remaining];
            raf.readFully(data);
            String checksum = sha256Bytes(data);
            return new ChunkData(chunkIndex, data, checksum);
        }
    }

    /**
     * 标记下载块已完成（受控端确认接收）
     */
    public void markChunkDownloaded(String transferId, int chunkIndex) {
        TransferProgress progress = getProgress(transferId);
        if (progress == null) return;
        Set<Integer> doneChunks = getDoneChunks(transferId);
        if (!doneChunks.contains(chunkIndex)) {
            redisTemplate.opsForSet().add(PROGRESS_KEY_PREFIX + transferId + CHUNKS_KEY_SUFFIX, chunkIndex);
            doneChunks = getDoneChunks(transferId);
            progress.setDoneChunks(doneChunks.size());
            int chunkSize = properties.getAgent().getFileTransferChunkSize();
            progress.setDoneBytes((long) doneChunks.size() * chunkSize);
            if (doneChunks.size() >= progress.getTotalChunks()) {
                progress.setStatus("completed");
            }
            progress.setUpdatedAt(System.currentTimeMillis());
            saveProgress(progress);
        }
    }

    // ========== 进度查询（任务 7.6）==========

    public TransferProgress getProgress(String transferId) {
        Object obj = redisTemplate.opsForValue().get(PROGRESS_KEY_PREFIX + transferId);
        if (obj instanceof TransferProgress) {
            return (TransferProgress) obj;
        }
        return null;
    }

    public Map<String, TransferProgress> listProgress(String instanceNumber) {
        // 简化：扫描所有传输进度（生产环境应用索引）
        Set<String> keys = redisTemplate.keys(PROGRESS_KEY_PREFIX + "*");
        Map<String, TransferProgress> result = new HashMap<>();
        if (keys != null) {
            for (String key : keys) {
                if (key.endsWith(CHUNKS_KEY_SUFFIX)) continue;
                Object obj = redisTemplate.opsForValue().get(key);
                if (obj instanceof TransferProgress tp) {
                    if (instanceNumber == null || instanceNumber.equals(tp.getInstanceNumber())) {
                        result.put(tp.getTransferId(), tp);
                    }
                }
            }
        }
        return result;
    }

    // ========== 辅助方法 ==========

    private void saveProgress(TransferProgress progress) {
        redisTemplate.opsForValue().set(PROGRESS_KEY_PREFIX + progress.getTransferId(), progress);
    }

    private Set<Integer> getDoneChunks(String transferId) {
        Set<Object> members = redisTemplate.opsForSet().members(PROGRESS_KEY_PREFIX + transferId + CHUNKS_KEY_SUFFIX);
        Set<Integer> result = new HashSet<>();
        if (members != null) {
            for (Object m : members) {
                if (m instanceof Integer) result.add((Integer) m);
                else if (m instanceof Number) result.add(((Number) m).intValue());
            }
        }
        return result;
    }

    private String resolveTargetPath(String transferId, String fileName, String type) {
        String base = properties.getStorage().getMigrationStagingDir();
        if ("image".equals(type)) base = properties.getStorage().getImageTarDir();
        else if ("public-image".equals(type)) base = properties.getStorage().getPublicImageDir();
        return base + "/" + transferId + "_" + fileName;
    }

    private String resolveSourcePath(TransferProgress progress) {
        String base = properties.getStorage().getMigrationStagingDir();
        if ("image".equals(progress.getType())) base = properties.getStorage().getImageTarDir();
        else if ("public-image".equals(progress.getType())) base = properties.getStorage().getPublicImageDir();
        return base + "/" + progress.getFileName();
    }

    private void ensureFileExists(String path, long size) {
        try {
            Path p = Paths.get(path);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) {
                try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                    raf.setLength(size);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("创建文件失败: " + path, e);
        }
    }

    private void writeChunk(String path, long offset, byte[] data) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(offset);
            raf.write(data);
        } catch (IOException e) {
            throw new RuntimeException("写入块失败", e);
        }
    }

    private boolean verifyChunkChecksum(byte[] data, String expected) {
        if (expected == null || expected.isBlank()) return true;
        return sha256Bytes(data).equals(expected);
    }

    private String sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return bytesToHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String sha256File(String path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] data = Files.readAllBytes(Paths.get(path));
            byte[] hash = md.digest(data);
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new IOException("计算校验和失败", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Data
    @AllArgsConstructor
    public static class ChunkData {
        private int index;
        private byte[] data;
        private String checksum;
    }
}
