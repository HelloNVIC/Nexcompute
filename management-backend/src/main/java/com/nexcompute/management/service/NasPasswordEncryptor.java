package com.nexcompute.management.service;

import com.nexcompute.management.config.NasProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM 密码加密（nas-allocation D7），1:1 对照原 Python 门户 PasswordEncryptor。
 * <p>密文格式：{@code base64-urlsafe(nonce12 + ciphertext + tag16)}。
 * Java GCM {@code doFinal} 默认 tag 在尾部，与 Python {@code AESGCM.encrypt} 输出一致，密文互通。
 * <p>密钥 base64-urlsafe 解码后须 16/24/32 字节。无 .env 时密钥为空，应用仍可启动（D10/D4 回退），
 * encrypt/decrypt 抛 {@link NasCryptoException}（到注册提交/批准才暴露）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NasPasswordEncryptor {

    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final NasProperties nasProperties;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        String keyB64 = nasProperties.getPasswordEncKey();
        if (keyB64 == null || keyB64.isBlank()) {
            log.warn("[NAS] PASSWORD_ENC_KEY 未配置，NAS 注册提交/开通不可用（配置 nas.password-enc-key 后生效）");
            this.secretKey = null;
            return;
        }
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(keyB64);
            if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                throw new IllegalArgumentException("解码后须 16/24/32 字节，实际 " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("[NAS] 密码加密密钥已加载（{} 字节）", keyBytes.length);
        } catch (Exception e) {
            log.warn("[NAS] PASSWORD_ENC_KEY 无效（{}），NAS 注册提交/开通不可用", e.getMessage());
            this.secretKey = null;
        }
    }

    /** 加密明文密码 -> base64-urlsafe(nonce12 + ct + tag16) */
    public String encrypt(String plaintext) {
        ensureKey();
        try {
            byte[] nonce = new byte[NONCE_LEN];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ctWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ctWithTag.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ctWithTag, 0, combined, nonce.length, ctWithTag.length);
            return Base64.getUrlEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new NasCryptoException("密码加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密密文 -> 明文；密钥不匹配或密文被篡改抛 {@link NasCryptoException} */
    public String decrypt(String token) {
        ensureKey();
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token);
        } catch (Exception e) {
            throw new NasCryptoException("密文非合法 base64", e);
        }
        if (raw.length <= NONCE_LEN) {
            throw new NasCryptoException("密文过短");
        }
        byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LEN);
        byte[] ctWithTag = Arrays.copyOfRange(raw, NONCE_LEN, raw.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(ctWithTag);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new NasCryptoException("密码解密失败（密钥不匹配或密文被篡改）: " + e.getMessage(), e);
        }
    }

    private void ensureKey() {
        if (secretKey == null) {
            throw new NasCryptoException("PASSWORD_ENC_KEY 未配置或无效，无法加解密密码");
        }
    }
}
