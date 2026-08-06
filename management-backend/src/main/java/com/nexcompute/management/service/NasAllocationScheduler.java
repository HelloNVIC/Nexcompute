package com.nexcompute.management.service;

import com.nexcompute.management.config.NasProperties;
import com.nexcompute.management.repository.NasRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * NAS pending 超期扫描（nas-allocation D9），复用平台既有 @Scheduled 调度。
 * 每 {@code nascompute.nas.expiry-scan-interval-hours}（默认 6）扫描 PENDING 且 submitted_at 超 pendingExpireDays 的行，
 * 擦 password_enc 并置 REJECTED（单条 bulk UPDATE，幂等不阻塞业务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NasAllocationScheduler {

    private static final String EXPIRE_REASON = "pending expired";

    private final NasRegistrationRepository registrationRepository;
    private final NasProperties nasProperties;

    @Scheduled(fixedDelayString = "${nascompute.nas.expiry-scan-interval-hours:6}h")
    @Transactional
    public void scanExpiredPending() {
        int expireDays = nasProperties.getPendingExpireDays();
        if (expireDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(expireDays, ChronoUnit.DAYS);
        int expired = registrationRepository.expirePending(EXPIRE_REASON, cutoff);
        if (expired > 0) {
            log.info("[NAS-Expiry] PENDING 超期扫描：置 REJECTED + 擦密码，count={}, cutoff={}", expired, cutoff);
        } else {
            log.debug("[NAS-Expiry] PENDING 超期扫描：无命中, cutoff={}", cutoff);
        }
    }
}
