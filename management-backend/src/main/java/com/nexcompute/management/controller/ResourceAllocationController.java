package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.MachineAllocation;
import com.nexcompute.management.domain.RegistrationLink;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.ResourceAllocationService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 学生资源分配接口（任务 3.3、3.4、3.5 中的链接管理 + 机器分配）
 */
@RestController
@RequestMapping("/allocations")
@RequiredArgsConstructor
@RequirePermission(module = "group", action = RequirePermission.Action.EDIT)
public class ResourceAllocationController {

    private final ResourceAllocationService allocationService;

    /** 创建注册链接（任务 3.3） */
    @PostMapping("/registration-links")
    public ApiResponse<RegistrationLink> createLink(@RequestBody CreateLinkRequest request) {
        return ApiResponse.success(allocationService.createRegistrationLink(
                request.getRemainingCount(), request.getExpireAt()));
    }

    /** 作废注册链接（任务 3.4） */
    @PostMapping("/registration-links/{token}/revoke")
    public ApiResponse<Void> revokeLink(@PathVariable String token) {
        allocationService.revokeLink(token);
        return ApiResponse.success();
    }

    /** 导师查看自己的注册链接 */
    @GetMapping("/registration-links")
    public ApiResponse<List<RegistrationLink>> myLinks() {
        return ApiResponse.success(allocationService.myLinks());
    }

    /** 导师分配机器给学生（platform-refinements 8.2：扩展单容器内存上限） */
    @PostMapping("/machines")
    public ApiResponse<MachineAllocation> allocate(@RequestBody AllocateMachineRequest request) {
        return ApiResponse.success(allocationService.allocateMachine(
                request.getInstanceId(), request.getStudentId(),
                request.getGroupId(), request.getPerContainerMemoryMb()));
    }

    /** 管理员按课题组分配物理实例（platform-refinements 8.1） */
    @PostMapping("/machines/group")
    public ApiResponse<List<MachineAllocation>> allocateGroup(@RequestBody AllocateGroupRequest request) {
        return ApiResponse.success(allocationService.allocateMachineToGroup(
                request.getInstanceId(), request.getGroupId()));
    }

    /** 课题组已分配资源列表（platform-refinements #4） */
    @GetMapping("/groups")
    public ApiResponse<List<ResourceAllocationService.GroupAllocationDto>> groupAllocations() {
        return ApiResponse.success(allocationService.listGroupAllocations());
    }

    /** 撤销分配影响检查（platform-refinements #3） */
    @GetMapping("/machines/{id}/impact")
    public ApiResponse<java.util.Map<String, Object>> impact(@PathVariable Long id) {
        return ApiResponse.success(allocationService.allocationImpact(id));
    }

    /** 撤销机器分配 */
    @DeleteMapping("/machines/{id}")
    public ApiResponse<Void> deallocate(@PathVariable Long id) {
        allocationService.deallocateMachine(id);
        return ApiResponse.success();
    }

    /** 撤销课题组在指定实例上的分配影响检查（撤销前提示） */
    @GetMapping("/groups/{instanceId}/{groupId}/impact")
    public ApiResponse<java.util.Map<String, Object>> groupImpact(@PathVariable Long instanceId,
                                                                  @PathVariable Long groupId) {
        return ApiResponse.success(allocationService.groupAllocationImpact(instanceId, groupId));
    }

    /** 撤销课题组在指定实例上的分配 */
    @DeleteMapping("/groups/{instanceId}/{groupId}")
    public ApiResponse<Integer> deallocateGroup(@PathVariable Long instanceId, @PathVariable Long groupId) {
        return ApiResponse.success(allocationService.deallocateGroup(instanceId, groupId));
    }

    /** 学生查看分配给自己的机器 */
    @GetMapping("/machines/my")
    public ApiResponse<List<MachineAllocation>> myMachines() {
        return ApiResponse.success(allocationService.myAllocatedMachines());
    }

    @Data
    public static class CreateLinkRequest {
        @Min(1)
        private int remainingCount;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant expireAt;
    }

    @Data
    public static class AllocateMachineRequest {
        @NotNull
        private Long instanceId;
        @NotNull
        private Long studentId;
        private Long groupId;
        /** 单容器内存上限（MB，可空=不限，platform-refinements 8.2） */
        private Integer perContainerMemoryMb;
    }

    @Data
    public static class AllocateGroupRequest {
        @NotNull
        private Long instanceId;
        @NotNull
        private Long groupId;
    }
}
