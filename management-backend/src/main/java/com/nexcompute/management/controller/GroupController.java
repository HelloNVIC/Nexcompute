package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.dto.UserInfoDto;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.ResearchGroupService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 课题组管理接口（任务 3.2）
 */
@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupController {

    private final ResearchGroupService groupService;

    /** 获取我的课题组（导师/学生） */
    @GetMapping("/my")
    public ApiResponse<ResearchGroup> myGroup() {
        return ApiResponse.success(groupService.getMyGroup());
    }

    /** 管理员查看所有课题组 */
    @GetMapping
    @RequirePermission(module = "user", action = RequirePermission.Action.VIEW)
    public ApiResponse<List<ResearchGroup>> list() {
        return ApiResponse.success(groupService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<ResearchGroup> get(@PathVariable Long id) {
        return ApiResponse.success(groupService.getGroup(id));
    }

    @PutMapping("/{id}")
    @RequirePermission(module = "group", action = RequirePermission.Action.EDIT)
    public ApiResponse<ResearchGroup> update(@PathVariable Long id, @RequestBody UpdateGroupRequest request) {
        return ApiResponse.success(groupService.updateGroup(id, request.getName(), request.getDescription()));
    }

    /** 删除课题组（platform-improvements 任务 5.3，管理员） */
    @DeleteMapping("/{id}")
    @RequirePermission(module = "user", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ApiResponse.success();
    }

    @PostMapping
    @RequirePermission(module = "user", action = RequirePermission.Action.EDIT)
    public ApiResponse<ResearchGroup> create(@RequestBody UpdateGroupRequest request) {
        return ApiResponse.success(groupService.createGroup(request.getName(), request.getDescription(), request.getMentorId()));
    }

    /** 课题组学生列表 */
    @GetMapping("/{id}/students")
    public ApiResponse<List<UserInfoDto>> students(@PathVariable Long id) {
        List<User> students = groupService.getGroupStudents(id);
        return ApiResponse.success(students.stream()
                .map(u -> UserInfoDto.from(u, null))
                .toList());
    }

    /** 按工号加入课题组成员（platform-refinements #6） */
    @PostMapping("/{id}/members")
    @RequirePermission(module = "group", action = RequirePermission.Action.EDIT)
    public ApiResponse<Void> addMember(@PathVariable Long id, @RequestBody AddMemberRequest request) {
        groupService.addMemberByWorkerId(id, request.getWorkerId());
        return ApiResponse.success();
    }

    /** 移除课题组成员（转无课题组，platform-refinements #6） */
    @DeleteMapping("/{id}/members/{userId}")
    @RequirePermission(module = "group", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        groupService.removeMember(id, userId);
        return ApiResponse.success();
    }

    @Data
    public static class AddMemberRequest {
        private String workerId;
    }

    @Data
    public static class UpdateGroupRequest {
        private String name;
        private String description;
        private Long mentorId;
    }
}
