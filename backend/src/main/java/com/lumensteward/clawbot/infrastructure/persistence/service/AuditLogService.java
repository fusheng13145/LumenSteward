package com.lumensteward.clawbot.infrastructure.persistence.service;

/**
 * 审计日志服务（架构 5.4 / SRS FR-16，BR-22）。
 */
public interface AuditLogService {

    /**
     * 记录审计。
     *
     * @param adminId 操作人（系统任务为 null）
     * @param regType 资源类型（CONFIG/USER/PROFILE/AUTH/DATA_DELETE）
     * @param action  操作标识（如 UPDATE/CREATE/DELETE）
     * @param target  操作对象（脱敏标识）
     * @param before  变更前值（须已脱敏）
     * @param after   变更后值（须已脱敏）
     * @param reason  变更原因
     * @param ip      来源 IP
     * @param result  结果：0-失败 1-成功
     */
    void record(Long adminId, String regType, String action, String target, String before,
                String after, String reason, String ip, int result);
}
