package com.nexcompute.management.domain;

/**
 * NAS 注册申请状态机（nas-allocation D2，1:1 对照原 Python 门户 5 态）。
 * <ul>
 *   <li>PENDING - 已提交待审批（密码 AES-GCM 暂存）</li>
 *   <li>APPROVED - 已批准并开通 TrueNAS 用户（密码已擦除）</li>
 *   <li>REJECTED - 已拒绝（密码已擦除，名额不退，用户名释放）</li>
 *   <li>FAILED - 开通失败（密码保留可重试）</li>
 *   <li>NOT_FOUND - TrueNAS 用户已删（可重申上游用新密码重建）</li>
 * </ul>
 */
public final class NasRegistrationStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";

    private NasRegistrationStatus() {}
}
