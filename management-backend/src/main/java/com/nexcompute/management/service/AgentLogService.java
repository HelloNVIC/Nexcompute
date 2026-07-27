package com.nexcompute.management.service;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.repository.PhysicalInstanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 受控端日志查看服务（platform-audit-logging-ux D8）。
 * 管理端不留存日志，现拉现返回：经 agent.log 命令向受控端拉取日志文件列表与尾 N 行。
 * 日志元数据不入库，按需现拉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLogService {

    private final AgentCommandService agentCommandService;
    private final PhysicalInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;

    /** 列受控端日志目录文件列表 */
    public List<Map<String, Object>> listFiles(Long instanceId) {
        String instanceNumber = resolveInstanceNumber(instanceId);
        if (!agentCommandService.isAgentConnected(instanceNumber)) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "受控端离线");
        }
        Map<String, Object> payload = Map.of("mode", "list");
        AgentCommandResult result = agentCommandService.sendCommand(instanceNumber, "agent.log", payload, 30000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    result != null ? result.getError() : "受控端无响应");
        }
        return parseFiles(result.getOutput());
    }

    /** 拉取指定日期日志尾 N 行（默认 500） */
    public Map<String, Object> tailLog(Long instanceId, String date, Integer tailLines) {
        String instanceNumber = resolveInstanceNumber(instanceId);
        if (!agentCommandService.isAgentConnected(instanceNumber)) {
            throw new BusinessException(ErrorCode.AGENT_NOT_CONNECTED, "受控端离线");
        }
        var payload = new java.util.HashMap<String, Object>();
        payload.put("mode", "tail");
        payload.put("date", date == null ? "" : date);
        payload.put("tailLines", tailLines == null ? 500 : tailLines);
        AgentCommandResult result = agentCommandService.sendCommand(instanceNumber, "agent.log", payload, 30000);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    result != null ? result.getError() : "受控端无响应");
        }
        return parseObject(result.getOutput());
    }

    private String resolveInstanceNumber(Long instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTANCE_NOT_FOUND))
                .getInstanceNumber();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFiles(String output) {
        try {
            JsonNode node = objectMapper.readTree(output);
            JsonNode files = node.path("files");
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            if (files.isArray()) {
                for (JsonNode f : files) {
                    result.add(objectMapper.convertValue(f, Map.class));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[AgentLog] 解析文件列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String output) {
        try {
            return objectMapper.readValue(output, Map.class);
        } catch (Exception e) {
            log.warn("[AgentLog] 解析尾行内容失败: {}", e.getMessage());
            return Map.of("content", "");
        }
    }
}
