package com.nexcompute.management.service;

/**
 * TrueNAS API key 权限不足（401/403 或错误体含权限标记，如 ACCOUNT_WRITE 缺失）。
 * ACCOUNT_WRITE 不可预检（TrueNAS REST 无 auth.me），到 {@code user.create} 时才暴露为 FAILED。
 * 1:1 对照原 Python 门户 TrueNASPermissionError。
 */
public class TrueNasPermissionError extends TrueNasApiError {

    public TrueNasPermissionError(String message, String method, Integer httpStatus) {
        super(message, method, httpStatus);
    }
}
