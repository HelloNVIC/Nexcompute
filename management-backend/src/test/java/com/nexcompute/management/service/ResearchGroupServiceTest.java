package com.nexcompute.management.service;

import com.nexcompute.management.domain.GroupMember;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.repository.GroupMemberRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ResearchGroupService 单元测试（platform-improvements 任务 5.6）
 * 验证管理员删除课题组（清理成员关系，用户 group_id 置空）。
 */
@ExtendWith(MockitoExtension.class)
class ResearchGroupServiceTest {

    @Mock
    private ResearchGroupRepository groupRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ResearchGroupService groupService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentRole)
                .thenReturn(com.nexcompute.management.domain.UserRole.ADMIN);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    void deleteGroup_cleansMembersAndClearsUserGroup() {
        ResearchGroup group = ResearchGroup.builder().id(5L).name("组A").mentorId(1L).build();
        when(groupRepository.findById(5L)).thenReturn(Optional.of(group));
        GroupMember member = GroupMember.builder().id(1L).groupId(5L).userId(2L).build();
        when(groupMemberRepository.findByGroupId(5L)).thenReturn(List.of(member));
        User student = User.builder().id(2L).groupId(5L).build();
        when(userRepository.findByGroupId(5L)).thenReturn(List.of(student));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        groupService.deleteGroup(5L);

        verify(groupMemberRepository).delete(member);       // 清理成员关系
        assertThat(student.getGroupId()).isNull();            // 用户 group_id 置空
        verify(groupRepository).delete(group);               // 删除课题组
    }
}
