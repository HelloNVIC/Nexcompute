package com.nexcompute.management.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 推送服务（任务 11.4、13.8）
 * 监控数据与通知消息复用同一套 SSE 基础设施，通过事件类型区分通道。
 */
@Slf4j
@Service
public class SseService {

    private static final long TIMEOUT = 0L; // 无超时

    /** userId -> SseEmitter */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT);
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> {
            emitters.remove(userId);
            log.debug("[SSE] 用户 {} 连接关闭", userId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(userId);
            emitter.complete();
        });
        emitter.onError(e -> {
            emitters.remove(userId);
            log.debug("[SSE] 用户 {} 连接错误: {}", userId, e.getMessage());
        });

        // 发送连接成功事件
        try {
            emitter.send(SseEmitter.event().name("connected").data("connected"));
        } catch (IOException e) {
            emitters.remove(userId);
        }

        log.info("[SSE] 用户 {} 已订阅", userId);
        return emitter;
    }

    /**
     * 向指定用户推送事件（任务 11.4 / 13.8）
     *
     * @param userId    目标用户
     * @param eventType 事件类型：monitoring / notification / ticket / container / storage-pool
     * @param data      数据
     */
    public void pushToUser(Long userId, String eventType, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return; // 用户离线，通知入库累积（任务 13.8 spec）
        }
        try {
            emitter.send(SseEmitter.event().name(eventType).data(data));
        } catch (IOException e) {
            emitters.remove(userId);
            log.debug("[SSE] 推送失败，移除用户 {}: {}", userId, e.getMessage());
        }
    }

    /**
     * 向所有在线用户广播
     */
    public void broadcast(String eventType, Object data) {
        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(data));
            } catch (IOException e) {
                emitters.remove(userId);
            }
        });
    }

    /** 向某实例相关用户推送监控数据（任务 11.4） */
    public void pushMonitoring(Long userId, Object data) {
        pushToUser(userId, "monitoring", data);
    }

    /** 推送通知（任务 13.8） */
    public void pushNotification(Long userId, Object data) {
        pushToUser(userId, "notification", data);
    }
}
