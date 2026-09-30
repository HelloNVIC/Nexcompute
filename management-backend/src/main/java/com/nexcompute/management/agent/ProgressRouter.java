package com.nexcompute.management.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受控端 progress 消息通用路由（registry-image-distribution D6）。
 *
 * 此前 AgentWebSocketHandler 将 progress 消息硬路由到 OtaProgressTracker；
 * 泛化为按 commandId 注册/注销 listener：OTA 升级与镜像拉取（image.pull -> SSE）均经本路由分发。
 * 无 listener 的 progress 消息（如命令已结束后的迟到帧）直接丢弃。
 */
@Slf4j
@Component
public class ProgressRouter {

    /** 进度监听器：text 为可选分层文本（image.pull 新增，OTA 不使用为 null）。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String commandId, String stage, Integer percent, String text);
    }

    /** commandId -> listener（同一命令仅一个 listener，后注册覆盖） */
    private final Map<String, ProgressListener> listeners = new ConcurrentHashMap<>();

    /** 注册：命令下发前调用（listener 存活期间接收该命令全部 progress 帧）。 */
    public void register(String commandId, ProgressListener listener) {
        if (commandId == null || listener == null) return;
        listeners.put(commandId, listener);
    }

    /** 注销：命令结束（成功/失败/超时）后调用，防泄漏。 */
    public void unregister(String commandId) {
        if (commandId != null) {
            listeners.remove(commandId);
        }
    }

    /** 分发一帧进度；无 listener 静默丢弃。listener 异常不影响其他帧。 */
    public void dispatch(String commandId, String stage, Integer percent, String text) {
        ProgressListener listener = listeners.get(commandId);
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(commandId, stage, percent, text);
        } catch (Exception e) {
            log.warn("[ProgressRouter] listener 处理进度失败: commandId={} err={}", commandId, e.getMessage());
        }
    }
}
