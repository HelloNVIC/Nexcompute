package com.nexcompute.management.service;

/**
 * NewAPI 网络/超时错误（连接拒绝、读超时、协议错误等）。
 * 重试耗尽后抛出；{@code refreshAllStatuses} 命中此类时不翻转状态（NewAPI 不可达，保持原状态）。
 * 1:1 对照 nas-allocation 的 TrueNasConnectionError。
 */
public class NewApiConnectionError extends RuntimeException {

    public NewApiConnectionError(String message) {
        super(message);
    }

    public NewApiConnectionError(String message, Throwable cause) {
        super(message, cause);
    }
}
