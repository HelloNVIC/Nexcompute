package com.nexcompute.management.service;

/**
 * NewAPI 系统访问令牌权限不足（401/403 或错误体含权限标记）。
 * 到 {@code userCreate} 时才暴露为 FAILED（启动 ping 不阻断，D10）。
 * 1:1 对照 nas-allocation 的 TrueNasPermissionError。
 */
public class NewApiPermissionError extends NewApiApiError {

    public NewApiPermissionError(String message, String method, Integer httpStatus) {
        super(message, method, httpStatus);
    }
}
