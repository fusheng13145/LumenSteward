package com.lumensteward.clawbot.application.retention;

import java.time.LocalDateTime;

/**
 * 数据保留与清理服务（FR-19 ① 定时清理）。
 *
 * <p>保留策略（OI-06）：会话消息 180 天、工具调用日志 180 天（脱敏后可延长）、
 * 已软删除宠物档案 30 天后物理清除。
 */
public interface DataRetentionService {

    /** 消息保留天数。 */
    int MESSAGE_RETENTION_DAYS = 180;
    /** 工具日志保留天数。 */
    int TOOL_LOG_RETENTION_DAYS = 180;
    /** 软删档案物理清除宽限期。 */
    int PET_SOFT_DELETE_GRACE_DAYS = 30;

    /**
     * 清理早于截止时间的会话消息。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    int purgeExpiredMessages(LocalDateTime cutoff);

    /**
     * 清理早于截止时间的会话。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    int purgeExpiredSessions(LocalDateTime cutoff);

    /**
     * 清理早于截止时间的工具调用日志。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    int purgeExpiredToolLogs(LocalDateTime cutoff);

    /**
     * 物理清除早于截止时间且已软删的宠物档案。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    int purgePhysicallyDeletedPets(LocalDateTime cutoff);

    /** 执行全量定时清理（每日凌晨调用）。 */
    void purgeAll();
}
