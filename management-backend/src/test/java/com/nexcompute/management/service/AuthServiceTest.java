package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.ResearchGroup;
import com.nexcompute.management.domain.RegistrationLink;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.domain.UserRole;
import com.nexcompute.management.dto.LoginRequest;
import com.nexcompute.management.dto.LoginResponse;
import com.nexcompute.management.dto.RegisterRequest;
import com.nexcompute.management.dto.UserInfoDto;
import com.nexcompute.management.repository.RegistrationLinkRepository;
import com.nexcompute.management.repository.ResearchGroupRepository;
import com.nexcompute.management.repository.UserRepository;
import com.nexcompute.management.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthService 单元测试（任务 14.1）
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ResearchGroupRepository groupRepository;
    @Mock
    private RegistrationLinkRepository registrationLinkRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .username("admin")
                .passwordHash("$2a$10$hashed")
                .realName("管理员")
                .role(UserRole.ADMIN)
                .status("ACTIVE")
                .build();
    }

    @Test
    void login_success() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("admin123", "$2a$10$hashed")).thenReturn(true);
        when(jwtUtil.generateToken(1L, "admin", "ADMIN")).thenReturn("jwt-token");

        LoginResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUser().getUsername()).isEqualTo("admin");
        verify(userRepository).findByUsername("admin");
    }

    @Test
    void login_wrongPassword_throws() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", "$2a$10$hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void login_disabledAccount_throws() {
        testUser.setStatus("DISABLED");
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void register_validLink_success() {
        RegistrationLink link = RegistrationLink.builder()
                .id(1L)
                .token("valid-token")
                .groupId(1L)
                .creatorId(2L)
                .remainingCount(5)
                .expireAt(Instant.now().plusSeconds(3600))
                .status("ACTIVE")
                .build();

        RegisterRequest request = new RegisterRequest();
        request.setToken("valid-token");
        request.setRealName("张三");
        request.setStudentId("2021001A");
        request.setPassword("pass123");
        request.setEmail("zhang@test.com");
        request.setPhone("13800000000");

        when(registrationLinkRepository.findByToken("valid-token")).thenReturn(Optional.of(link));
        when(userRepository.existsByUsername("2021001A")).thenReturn(false);
        when(passwordEncoder.encode("pass123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(10L);
            return u;
        });
        when(groupRepository.findById(1L)).thenReturn(Optional.of(ResearchGroup.builder().name("组1").build()));

        UserInfoDto result = authService.register(request);

        assertThat(result.getRealName()).isEqualTo("张三");
        assertThat(result.getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(link.getRemainingCount()).isEqualTo(4); // 扣减
    }

    @Test
    void register_invalidLink_throws() {
        RegisterRequest request = new RegisterRequest();
        request.setToken("invalid");

        when(registrationLinkRepository.findByToken("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REGISTRATION_LINK_INVALID);
    }

    @Test
    void register_expiredLink_throws() {
        RegistrationLink link = RegistrationLink.builder()
                .token("expired")
                .remainingCount(5)
                .expireAt(Instant.now().minusSeconds(3600)) // 已过期
                .status("ACTIVE")
                .build();

        RegisterRequest request = new RegisterRequest();
        request.setToken("expired");

        when(registrationLinkRepository.findByToken("expired")).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REGISTRATION_LINK_EXPIRED);
    }
}
