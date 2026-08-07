package com.nexcompute.management.repository;

import com.nexcompute.management.domain.NewApiInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewApiInvitationRepository extends JpaRepository<NewApiInvitation, Long> {

    /** 凭 token 查邀请（兑换/校验/查重用） */
    Optional<NewApiInvitation> findByToken(String token);

    /** 全部邀请按创建时间倒序（管理员列表） */
    List<NewApiInvitation> findAllByOrderByCreatedAtDesc();

    /** 仅有效（未撤销）邀请按创建时间倒序 */
    List<NewApiInvitation> findByRevokedAtIsNullOrderByCreatedAtDesc();

    /**
     * 原子消耗一个名额（D8）：单条条件 UPDATE，并发不超发。
     * 命中（受影响行数=1）即消耗成功；0 表示名额耗尽/过期/撤销，由调用方再读状态精确分类。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NewApiInvitation i SET i.usedCount = i.usedCount + 1 " +
            "WHERE i.token = :token AND i.usedCount < i.maxUses " +
            "AND i.revokedAt IS NULL AND i.expiresAt > CURRENT_TIMESTAMP")
    int redeem(@Param("token") String token);
}
