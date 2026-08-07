package com.nexcompute.management.service;

import lombok.Getter;

/**
 * NewAPI REST API 返回的错误响应（HTTP >= 400，或 2xx 但 success=false）。
 * 含 {@link #method}（如 "POST /api/user/"）与 {@link #httpStatus}；
 * 404 用于 {@code NewApiRegistrationService} 将 APPROVED 行翻转为 NOT_FOUND。
 * 1:1 对照 nas-allocation 的 TrueNasApiError。
 */
@Getter
public class NewApiApiError extends RuntimeException {

    private final String method;
    private final Integer httpStatus;

    public NewApiApiError(String message, String method, Integer httpStatus) {
        super(message);
        this.method = method;
        this.httpStatus = httpStatus;
    }

    public NewApiApiError(String message, String method, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.method = method;
        this.httpStatus = httpStatus;
    }
}
