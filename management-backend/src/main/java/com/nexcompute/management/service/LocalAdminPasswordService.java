package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.LocalAdminPassword;
import com.nexcompute.management.repository.LocalAdminPasswordRepository;
import com.nexcompute.management.agent.AgentCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 全局受控端管理密码服务（platform-refinements 11.1/11.2）。
 * 所有受控端共用一个明文密码，单一设置后广播至所有已连接受控端；离线受控端经心跳回包补推。
 * 用户明确要求明文传输与保存，接受该风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalAdminPasswordService {

    private final LocalAdminPasswordRepository passwordRepository;
    private final AgentCommandService agentCommandService;

    /**
     * 设置全局明文密码并广播 config.set_admin_password 至所有已连接受控端。
     */
    @Audited(action = "LOCAL_ADMIN_PASSWORD_SET", targetType = "SYSTEM", targetIdExpr = "'global'")
    @Transactional
    public void setGlobalPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码不能为空");
        }
        LocalAdminPassword pwd = passwordRepository.findGlobal()
                .orElseGet(() -> LocalAdminPassword.builder().id((short) 1).build());
        pwd.setPassword(password);
        passwordRepository.save(pwd);

        int sent = agentCommandService.broadcast("config.set_admin_password", Map.of("password", password));
        log.info("[LocalAdminPwd] 全局密码已设置并下发至 {} 台已连接受控端", sent);
    }

    /** 当前全局密码（供心跳回包推送给刚上线的受控端，11.2）。无则返回 null。 */
    public String getGlobalPassword() {
        return passwordRepository.findGlobal().map(LocalAdminPassword::getPassword).orElse(null);
    }

    /** 是否已设置全局密码（platform-refinements：修改前询问用） */
    public boolean isSet() {
        return passwordRepository.findGlobal().isPresent();
    }
}
