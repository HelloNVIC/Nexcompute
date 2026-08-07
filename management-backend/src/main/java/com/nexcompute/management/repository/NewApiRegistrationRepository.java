package com.nexcompute.management.repository;

import com.nexcompute.management.domain.NewApiRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface NewApiRegistrationRepository extends JpaRepository<NewApiRegistration, Long> {

    /** 全部申请按提交时间倒序（管理员列表，无 status 过滤） */
    List<NewApiRegistration> findAllByOrderBySubmittedAtDesc();

    /** 按 status 过滤的申请列表（管理员列表） */
    List<NewApiRegistration> findByStatusOrderBySubmittedAtDesc(String status);

    /**
     * 用户名占用查重：仅 PENDING/APPROVED/FAILED 占用该用户名，REJECTED 释放可再注册。
     * 调用方传入 statuses=[PENDING, APPROVED, FAILED]。
     */
    boolean existsByUsernameAndStatusIn(String username, Collection<String> statuses);

    /** 刷新状态用：所有 APPROVED 且已回填 newapi_user_id 的行（复核 NewAPI 是否还在） */
    List<NewApiRegistration> findByStatusAndNewapiUserIdIsNotNull(String status);

    /**
     * PENDING 超期扫描（D9）：单条 bulk UPDATE 置 REJECTED + 擦 password_enc + 记原因。
     * 幂等（已 REJECTED 不再命中），返回命中行数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NewApiRegistration r SET r.status = 'REJECTED', r.passwordEnc = NULL, " +
            "r.rejectReason = :reason, r.reviewedAt = CURRENT_TIMESTAMP " +
            "WHERE r.status = 'PENDING' AND r.submittedAt < :cutoff")
    int expirePending(@Param("reason") String reason, @Param("cutoff") Instant cutoff);
}
