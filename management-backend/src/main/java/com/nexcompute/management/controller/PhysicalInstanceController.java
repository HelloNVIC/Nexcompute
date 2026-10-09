package com.nexcompute.management.controller;

import com.nexcompute.management.agent.AgentCommandResult;
import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.PhysicalInstance;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.security.SecurityUtils;
import com.nexcompute.management.service.PhysicalInstanceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 物理实例管理接口（任务 6.2-6.4）
 */
@RestController
@RequestMapping("/instances")
@RequiredArgsConstructor
public class PhysicalInstanceController {

    private final PhysicalInstanceService instanceService;

    /** 列表：管理员看全部，学生/导师看分配给自己的 */
    @GetMapping
    public ApiResponse<List<PhysicalInstance>> list() {
        UserRole role = SecurityUtils.getCurrentRole();
        if (role == UserRole.ADMIN) {
            return ApiResponse.success(instanceService.listAll());
        }
        return ApiResponse.success(instanceService.listAllocatedTo(SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<PhysicalInstance> get(@PathVariable Long id) {
        return ApiResponse.success(instanceService.getInstance(id));
    }

    /** 修改物理机编号（任务 6.2） */
    @PutMapping("/{id}/number")
    @RequirePermission(module = "physical-instance", action = RequirePermission.Action.EDIT)
    public ApiResponse<PhysicalInstance> updateNumber(@PathVariable Long id, @RequestBody UpdateNumberRequest request) {
        return ApiResponse.success(instanceService.updateInstanceNumber(id, request.getNumber()));
    }

    /** 删除物理实例（instance-identity，仅管理员）：在线与被占用实例拒绝；force=true 时
     *  跳过镜像同步任务检查并连同删除（同步任务为纯派生记录） */
    @DeleteMapping("/{id}")
    @RequirePermission(module = "physical-instance", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestParam(defaultValue = "false") boolean force) {
        instanceService.deleteInstance(id, force);
        return ApiResponse.success();
    }

    /** 远程重启（任务 6.3） */
    @PostMapping("/{id}/restart")
    @RequirePermission(module = "physical-instance", action = RequirePermission.Action.EDIT)
    public ApiResponse<AgentCommandResult> restart(@PathVariable Long id) {
        return ApiResponse.success(instanceService.restart(id));
    }

    /** 远程息屏（任务 6.3） */
    @PostMapping("/{id}/screen-off")
    @RequirePermission(module = "physical-instance", action = RequirePermission.Action.EDIT)
    public ApiResponse<AgentCommandResult> screenOff(@PathVariable Long id) {
        return ApiResponse.success(instanceService.screenOff(id));
    }

    /**
     * PowerShell 远程执行（任务 6.4）
     * 仅管理员
     */
    @PostMapping("/{id}/powershell")
    @RequirePermission(module = "physical-instance", action = RequirePermission.Action.EDIT)
    public ApiResponse<AgentCommandResult> powershell(@PathVariable Long id, @RequestBody PowerShellRequest request) {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可执行 PowerShell");
        }
        return ApiResponse.success(instanceService.executePowerShell(id, request.getCommand()));
    }

    @Data
    public static class UpdateNumberRequest {
        private String number;
    }

    @Data
    public static class PowerShellRequest {
        private String command;
    }
}
