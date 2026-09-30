package com.nexcompute.management.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.repository.AgentUpgradeTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTA 升级进度追踪器（platform-audit-logging-ux D2）。
 * 维护 commandId -> 升级状态（taskId/目标版本/各段百分比/推断段计时）。
 * progress 消息只更新 stagePercents，**绝不 complete future**（R1）。
 * ④ 替换重启段：收到 replacing 起按已耗时/30s 线性插值，进程退出（WS 断开）即满。
 * ⑤ 等待版本确认段：首次心跳起算，agentVersion==目标即满 + SUCCESS。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaProgressTracker implements ProgressRouter.ProgressListener {

    public static final String STAGE_DOWNLOADING = "downloading";
    public static final String STAGE_VERIFYING = "verifying";
    public static final String STAGE_BACKING_UP = "backing_up";
    public static final String STAGE_REPLACING = "replacing";
    public static final String STAGE_WAITING = "waiting";

    /** ④ 替换重启段预期耗时（秒），用于线性插值 */
    private static final long REPLACING_EXPECTED_MS = 30_000L;

    private final AgentUpgradeTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final ProgressRouter progressRouter;

    /** commandId -> 状态 */
    private final Map<String, UpgradeState> states = new ConcurrentHashMap<>();

    /** instanceNumber -> 当前升级 commandId（心跳/WS 断开时反查） */
    private final Map<String, String> instanceToCommand = new ConcurrentHashMap<>();

    public static class UpgradeState {
        public Long taskId;
        public Long instanceId;
        public String instanceNumber;
        public String targetVersion;
        public String commandId;
        public final Map<String, Integer> percents = newPercents();
        public volatile long replacingStartMs;   // 收到 replacing 起算
        public volatile long firstHeartbeatMs;    // 新版首次心跳起算
        public volatile boolean replaced;         // 受控端进程退出（WS 断开）
    }

    private static Map<String, Integer> newPercents() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(STAGE_DOWNLOADING, 0);
        m.put(STAGE_VERIFYING, 0);
        m.put(STAGE_BACKING_UP, 0);
        m.put(STAGE_REPLACING, 0);
        m.put(STAGE_WAITING, 0);
        return m;
    }

    /** OTA 服务下发命令前注册：绑定 commandId <-> task/instance（D6：同时向 ProgressRouter 注册本监听器） */
    public void register(String commandId, Long taskId, Long instanceId, String instanceNumber, String targetVersion) {
        UpgradeState s = new UpgradeState();
        s.commandId = commandId;
        s.taskId = taskId;
        s.instanceId = instanceId;
        s.instanceNumber = instanceNumber;
        s.targetVersion = targetVersion;
        states.put(commandId, s);
        if (instanceNumber != null) {
            instanceToCommand.put(instanceNumber, commandId);
        }
        progressRouter.register(commandId, this);
    }

    /**
     * 受控端回传 progress 消息（经 ProgressRouter 分发，D6）：更新 stage + 该段百分比，绝不 complete future（R1）。
     * OTA 不使用 text 分层文本（D5），忽略。
     */
    @Override
    public void onProgress(String commandId, String stage, Integer percent, String text) {
        UpgradeState s = states.get(commandId);
        if (s == null) {
            return;
        }
        if (stage != null) {
            s.percents.put(stage, clamp(percent == null ? 0 : percent));
            if (STAGE_REPLACING.equals(stage)) {
                // 收到 replacing -> 记录推断段起点（受控端即将退出）
                if (s.replacingStartMs == 0) {
                    s.replacingStartMs = System.currentTimeMillis();
                }
            }
        }
        persist(s);
    }

    /** 受控端回传最终 replacing Result：标记 ④ 段进入推断 */
    public void onReplacingResult(String commandId) {
        UpgradeState s = states.get(commandId);
        if (s == null) return;
        if (s.replacingStartMs == 0) {
            s.replacingStartMs = System.currentTimeMillis();
        }
        s.percents.put(STAGE_REPLACING, inferReplacingPercent(s));
        s.percents.put(STAGE_DOWNLOADING, 100);
        s.percents.put(STAGE_VERIFYING, 100);
        s.percents.put(STAGE_BACKING_UP, 100);
        persist(s);
    }

    /** 受控端 WS 断开（进程退出）：④ 替换重启段达 100%，进入 ⑤ 等待版本确认 */
    public void onAgentDisconnected(String instanceNumber) {
        String commandId = instanceToCommand.get(instanceNumber);
        if (commandId == null) return;
        UpgradeState s = states.get(commandId);
        if (s == null) return;
        s.replaced = true;
        s.percents.put(STAGE_REPLACING, 100);
        persist(s);
    }

    /**
     * 心跳到达：若属当前升级实例，更新 ⑤ 等待版本确认段。
     * @return true 若该心跳确认了目标版本（应判 SUCCESS）
     */
    public boolean onHeartbeat(String instanceNumber, String agentVersion) {
        String commandId = instanceToCommand.get(instanceNumber);
        if (commandId == null) return false;
        UpgradeState s = states.get(commandId);
        if (s == null) return false;
        if (s.firstHeartbeatMs == 0) {
            s.firstHeartbeatMs = System.currentTimeMillis();
        }
        if (s.targetVersion != null && s.targetVersion.equals(agentVersion)) {
            s.percents.put(STAGE_WAITING, 100);
            s.percents.put(STAGE_REPLACING, 100);
            persist(s);
            return true; // 版本对上 -> SUCCESS
        }
        // 版本未对上：⑤ 段持续爬升（异常态暴露）
        s.percents.put(STAGE_WAITING, inferWaitingPercent(s));
        persist(s);
        return false;
    }

    /** 推断 ④ 替换重启段百分比：已耗时/30s 线性插值，进程退出即满 */
    private int inferReplacingPercent(UpgradeState s) {
        if (s.replaced) return 100;
        if (s.replacingStartMs == 0) return 0;
        long elapsed = System.currentTimeMillis() - s.replacingStartMs;
        int pct = (int) (elapsed * 100 / REPLACING_EXPECTED_MS);
        return clamp(pct);
    }

    /** 推断 ⑤ 等待版本确认段百分比：首次心跳起算，缓慢爬升暴露异常态 */
    private int inferWaitingPercent(UpgradeState s) {
        if (s.firstHeartbeatMs == 0) return 0;
        long elapsed = System.currentTimeMillis() - s.firstHeartbeatMs;
        // 慢爬：每秒 +2%，封顶 99（版本对上才 100）
        int pct = (int) (elapsed / 1000 * 2);
        if (pct > 99) pct = 99;
        return clamp(pct);
    }

    /** 超时判 FAILED 时：停最后值，标记 ⑤ 段暴露版本未对上异常 */
    public void onTimeout(String commandId) {
        UpgradeState s = states.get(commandId);
        if (s == null) return;
        s.percents.put(STAGE_REPLACING, s.replaced ? 100 : inferReplacingPercent(s));
        persist(s);
    }

    /** 升级结束（成功/失败）清理（D6：同时从 ProgressRouter 注销） */
    public void finish(String commandId) {
        UpgradeState s = states.remove(commandId);
        if (s != null && s.instanceNumber != null) {
            instanceToCommand.remove(s.instanceNumber, commandId);
        }
        progressRouter.unregister(commandId);
    }

    private void persist(UpgradeState s) {
        if (s.taskId == null) return;
        try {
            taskRepository.findById(s.taskId).ifPresent(t -> {
                t.setProgressStage(currentStage(s));
                t.setStagePercents(writePercents(s));
                taskRepository.save(t);
            });
        } catch (Exception e) {
            log.warn("[OtaProgress] 持久化进度失败: {}", e.getMessage());
        }
    }

    private String currentStage(UpgradeState s) {
        // 当前段 = 第一个未满的段
        for (Map.Entry<String, Integer> e : s.percents.entrySet()) {
            if (e.getValue() < 100) return e.getKey();
        }
        return STAGE_WAITING;
    }

    private String writePercents(UpgradeState s) {
        try {
            return objectMapper.writeValueAsString(s.percents);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private int clamp(int p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }
}
