package com.nexcompute.management.service;

import lombok.Getter;

/**
 * TrueNAS REST API 返回的错误响应（HTTP >= 400）。
 * 含 {@link #method}（如 "POST /api/v2.0/user"）与 {@link #httpStatus}；
 * 404 用于 {@code NasRegistrationService} 将 APPROVED 行翻转为 NOT_FOUND。
 * 1:1 对照原 Python 门户 TrueNASAPIError。
 */
@Getter
public class TrueNasApiError extends RuntimeException {

    private final String method;
    private final Integer httpStatus;

    public TrueNasApiError(String message, String method, Integer httpStatus) {
        super(message);
        this.method = method;
        this.httpStatus = httpStatus;
    }

    public TrueNasApiError(String message, String method, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.method = method;
        this.httpStatus = httpStatus;
    }
}
