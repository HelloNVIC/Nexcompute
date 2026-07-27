package com.nexcompute.management.audit;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 最小 ULID 生成器（26 字符 Crockford base32，时间前置有序，规范兼容）。
 * 不引入外部依赖；满足"操作记录唯一编码（ULID 有序短码）"需求。
 * 注意：本实现不保证全局唯一于分布式高并发碰撞下绝对安全，但单节点 SecureRandom
 * 80 bit 随机 + 毫秒时间戳，碰撞概率可忽略；如需更严可换 com.github.f4b6a3:ulid-creator。
 */
public final class UlidGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_LEN = 10;
    private static final int RAND_LEN = 16;
    private static final SecureRandom RNG = new SecureRandom();

    private UlidGenerator() {}

    public static String next() {
        char[] ulid = new char[TIME_LEN + RAND_LEN];
        long ts = Instant.now().toEpochMilli();
        // 时间戳 48 bit -> 10 字符
        for (int i = TIME_LEN - 1; i >= 0; i--) {
            ulid[i] = ALPHABET[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        // 随机 80 bit -> 16 字符
        byte[] rand = new byte[10];
        RNG.nextBytes(rand);
        long hi = ((long) (rand[0] & 0xFF) << 32) | ((long) (rand[1] & 0xFF) << 24)
                | ((long) (rand[2] & 0xFF) << 16) | ((long) (rand[3] & 0xFF) << 8) | (rand[4] & 0xFF);
        long lo = ((long) (rand[5] & 0xFF) << 32) | ((long) (rand[6] & 0xFF) << 24)
                | ((long) (rand[7] & 0xFF) << 16) | ((long) (rand[8] & 0xFF) << 8) | (rand[9] & 0xFF);
        for (int i = RAND_LEN - 1; i >= 8; i--) {
            ulid[TIME_LEN + i] = ALPHABET[(int) (lo & 0x1F)];
            lo >>>= 5;
        }
        // hi 贡献高 8 字符
        for (int i = 7; i >= 0; i--) {
            ulid[TIME_LEN + i] = ALPHABET[(int) (hi & 0x1F)];
            hi >>>= 5;
        }
        return new String(ulid);
    }
}
