package com.nexcompute.management.service;

/**
 * TrueNAS 网络/超时错误（连接拒绝、读超时、协议错误等）。
 * 重试耗尽后抛出；{@code refreshAllStatuses} 命中此类时不翻转状态（TrueNAS 不可达，保持原状态）。
 * 1:1 对照原 Python 门户 TrueNASConnectionError。
 */
public class TrueNasConnectionError extends RuntimeException {

    public TrueNasConnectionError(String message) {
        super(message);
    }

    public TrueNasConnectionError(String message, Throwable cause) {
        super(message, cause);
    }
}
