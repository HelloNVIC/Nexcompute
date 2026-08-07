package com.nexcompute.management.service;

import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.NasRegistration;
import com.nexcompute.management.repository.NasRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * NAS 开通失败状态落盘（nas-allocation，与 NewApiProvisionFailureRecorder 同模式）。
 * <p>{@code NasRegistrationService.approve}/{@code reprovision} 为 {@code @Transactional}，
 * 开通失败时若在同事务内 {@code setStatus(FAILED)+setProvisionError+save} 再 {@code throw}，
 * 会因事务回滚而丢失 FAILED/provision_error（password_enc 因属上一笔已提交的 submit 事务而保留）。
 * 本组件以 {@link Propagation#REQUIRES_NEW} 在独立事务中落盘失败状态，提交后再抛出业务异常，
 * 确保 spec「开通失败保留密码可重试」（置 FAILED、写 provision_error、保留 password_enc）生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NasProvisionFailureRecorder {

    private final NasRegistrationRepository registrationRepository;

    /**
     * 在独立事务中记录开通失败：置 status + 写 provision_error，保留 password_enc 供重试。
     * 调用方在外层事务（仅含读操作）抛出 BusinessException 触发外层回滚，但本事务已独立提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long id, String status, String error) {
        NasRegistration reg = registrationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NAS_REGISTRATION_NOT_FOUND));
        reg.setStatus(status);
        reg.setProvisionError(error);
        // password_enc 不擦除，保留供重试
        registrationRepository.save(reg);
    }
}
