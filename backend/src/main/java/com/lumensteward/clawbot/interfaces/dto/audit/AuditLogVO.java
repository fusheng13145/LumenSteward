package com.lumensteward.clawbot.interfaces.dto.audit;

import java.time.LocalDateTime;

/**
 * 审计日志视图（架构 4.3 / GET /api/audit-logs）。
 *
 * @param id          主键
 * @param adminId     操作人（系统任务为空）
 * @param regType     资源类型：CONFIG/USER/PROFILE/AUTH/DATA_DELETE
 * @param action      操作标识
 * @param target      操作对象（脱敏标识）
 * @param beforeValue 变更前值（已脱敏）
 * @param afterValue  变更后值（已脱敏）
 * @param reason      变更原因
 * @param ip          来源 IP
 * @param result      结果：0-失败 1-成功
 * @param createdAt   创建时间
 */
public record AuditLogVO(Long id,
                         Long adminId,
                         String regType,
                         String action,
                         String target,
                         String beforeValue,
                         String afterValue,
                         String reason,
                         String ip,
                         Integer result,
                         LocalDateTime createdAt) {
}
