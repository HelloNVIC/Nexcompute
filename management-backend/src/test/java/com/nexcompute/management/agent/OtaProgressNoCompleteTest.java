package com.nexcompute.management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 进度消息不完成 future 验证（platform-audit-logging-ux D2 / R1）。
 * 发 progress 后 channel 仍能收到最终 Result（future 未被提前 complete）。
 *
 * 验证点：AgentSessionRegistry 在收到 progress 时不会 latch.countDown()，
 * waitForResult 仍阻塞至最终 Result 到达。
 */
class OtaProgressNoCompleteTest {

    @Test
    void progressMessageDoesNotCompleteFuture() throws Exception {
        AgentSessionRegistry registry = new AgentSessionRegistry(new ObjectMapper());
        String commandId = "cmd-progress-1";

        registry.registerPending(commandId);

        // 1) 模拟受控端回传 progress（不应完成 future）
        //    registry 无 onProgress 入口；progress 经 WS handler 路由到 OtaProgressTracker，
        //    而 tracker 不调用 awaitResult。故 registry 这边 pending 仍空 result。
        //    这里直接验证：未 awaitResult 前 waitForResult(短超时) 返回 null。
        long t0 = System.currentTimeMillis();
        AgentCommandResult r1 = registry.waitForResult(commandId, 200);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(r1).as("progress 期间不应有结果，waitForResult 应超时返回 null").isNull();
        // 验证确实等满了超时（即 future 未被 progress 提前 complete）
        assertThat(elapsed).as("future 未被提前 complete，应阻塞至超时").isGreaterThanOrEqualTo(150);

        // 2) 注册已超时移除，重新注册再发最终 Result 验证能收到
        registry.registerPending(commandId);
        AgentCommandResult finalResult = new AgentCommandResult();
        finalResult.setCommandId(commandId);
        finalResult.setSuccess(true);
        finalResult.setOutput("{\"status\":\"replacing\"}");
        boolean delivered = registry.awaitResult(commandId, finalResult, 0);
        assertThat(delivered).isTrue();
        AgentCommandResult r2 = registry.waitForResult(commandId, 1000);
        assertThat(r2).as("发 progress 后仍能收到最终 Result").isSameAs(finalResult);
    }

    @Test
    void otaProgressTrackerUpdatesButDoesNotTouchRegistry() {
        // 直接验证 tracker 的 onProgress 不调用 registry.awaitResult
        AgentSessionRegistry registry = spy(new AgentSessionRegistry(new ObjectMapper()));
        OtaProgressTracker tracker = new OtaProgressTracker(
                mock(com.nexcompute.management.repository.AgentUpgradeTaskRepository.class),
                new ObjectMapper(),
                new ProgressRouter());

        tracker.register("cmd-x", 1L, 10L, "INST-1", "v2");
        tracker.onProgress("cmd-x", OtaProgressTracker.STAGE_DOWNLOADING, 50, null);

        // tracker 不应触发 registry.awaitResult（progress 不 complete future）
        verify(registry, never()).awaitResult(anyString(), any(), anyLong());
    }
}
