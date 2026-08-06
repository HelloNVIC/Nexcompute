package com.nexcompute.management.service;

/**
 * NAS 密码加解密异常（密钥未配置/无效/密文被篡改或密钥不匹配）。
 * 1:1 对照原 Python 门户 CryptoError。
 */
public class NasCryptoException extends RuntimeException {

    public NasCryptoException(String message) {
        super(message);
    }

    public NasCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
