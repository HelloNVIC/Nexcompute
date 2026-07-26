package com.nexcompute.management.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 文件校验和工具（platform-env-ota-realtime）。
 * 环境文件与受控端 OTA exe 均以 MD5 记录与校验。
 */
public final class FileChecksums {

    private FileChecksums() {}

    /** 计算文件 MD5（十六进制小写） */
    public static String md5File(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("计算 MD5 失败", e);
        }
    }
}
