package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计接口
 * 按角色返回可见资源数量统计。
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final PhysicalInstanceRepository instanceRepository;
    private final ContainerRepository containerRepository;
    private final ImageMetadataRepository imageRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();

        Map<String, Object> result = new HashMap<>();

        if (role == UserRole.ADMIN) {
            // 管理员：全部统计
            result.put("instances", instanceRepository.count());
            result.put("containers", containerRepository.count());
            result.put("images", imageRepository.count());
            result.put("tickets", ticketRepository.count());
            result.put("users", userRepository.count());
        } else if (role == UserRole.MENTOR) {
            // 导师：课题组学生 + 自己的容器
            User mentor = userRepository.findById(userId).orElseThrow();
            long containers = containerRepository.findByOwnerId(userId).size();
            if (mentor.getGroupId() != null) {
                List<User> students = userRepository.findByGroupId(mentor.getGroupId());
                List<Long> studentIds = students.stream().map(User::getId).toList();
                studentIds.add(userId);
                containers += containerRepository.findByOwnerIdIn(studentIds).size()
                        - containerRepository.findByOwnerId(userId).size();
            }
            result.put("instances", instanceRepository.count());
            result.put("containers", containers);
            result.put("images", imageRepository.findByOwnerId(userId).size());
            result.put("tickets", ticketRepository.findBySubmitterIdOrderByCreatedAtDesc(userId).size());
        } else {
            // 学生：自己的资源
            result.put("instances", (long) instanceRepository.findAll().size());
            result.put("containers", (long) containerRepository.findByOwnerId(userId).size());
            result.put("images", (long) imageRepository.findByOwnerId(userId).size());
            result.put("tickets", (long) ticketRepository.findBySubmitterIdOrderByCreatedAtDesc(userId).size());
        }

        return ApiResponse.success(result);
    }
}
