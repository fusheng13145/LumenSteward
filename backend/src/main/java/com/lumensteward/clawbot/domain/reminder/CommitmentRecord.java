package com.lumensteward.clawbot.domain.reminder;

import com.lumensteward.clawbot.common.enums.CommitmentStatus;

import java.time.LocalDateTime;

/**
 * 承诺记录（迭代 4 W7/W8；domain 只读快照，持久化在 infrastructure）。
 *
 * @param id              数据库主键
 * @param openid          所属用户（敏感，出参须脱敏，BR-21）
 * @param content         要提醒的事
 * @param dueAt           到期时刻（登记时定格）
 * @param status          状态（PENDING → DELIVERED）
 * @param sourceSessionId 溯源：来源会话 id（W6 口径）
 * @param sourceTraceId   溯源：来源链路标识（FR-21）
 */
public record CommitmentRecord(Long id, String openid, String content, LocalDateTime dueAt,
                               CommitmentStatus status, Long sourceSessionId, String sourceTraceId) {

    /** 是否已投递（幂等闩的读侧视图）。 */
    public boolean delivered() {
        return status == CommitmentStatus.DELIVERED;
    }
}
