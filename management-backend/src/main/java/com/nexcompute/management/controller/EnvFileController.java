package com.nexcompute.management.controller;

import com.nexcompute.management.agent.AgentCommandService;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.EnvFile;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.EnvFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 受控端环境文件管理接口（D4）。
 * 管理员上传/列表/删除环境准备文件，手动"环境网盘同步"向在线受控端广播 env.sync。
 */
@Slf4j
@RestController
@RequestMapping("/admin/env-files")
@RequiredArgsConstructor
public class EnvFileController {

    private final EnvFileService envFileService;
    private final AgentCommandService agentCommandService;

    /** 上传环境文件（同文件名覆盖） */
    @PostMapping
    @RequirePermission(module = "agent-env", action = RequirePermission.Action.EDIT)
    public ApiResponse<EnvFile> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(envFileService.upload(file));
    }

    /** 列表 */
    @GetMapping
    @RequirePermission(module = "agent-env", action = RequirePermission.Action.VIEW)
    public ApiResponse<List<EnvFile>> list() {
        return ApiResponse.success(envFileService.list());
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    @RequirePermission(module = "agent-env", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        envFileService.delete(id);
        return ApiResponse.success();
    }

    /** "环境网盘同步"：向在线受控端广播 env.sync 命令（离线实例下次心跳后由定时同步补齐） */
    @PostMapping("/sync")
    @RequirePermission(module = "agent-env", action = RequirePermission.Action.EDIT)
    public ApiResponse<Map<String, Object>> sync() {
        int sent = agentCommandService.broadcast("env.sync", Map.of());
        log.info("[EnvFile] 环境网盘同步已向 {} 台在线受控端下发 env.sync", sent);
        return ApiResponse.success(Map.of("sent", sent));
    }
}
