package com.lumensteward.clawbot.application.retention;

import java.time.LocalDateTime;

/**
 * 数据保留与清理服务（FR-19 ① 定时清理）。
 *
 * <p>保留策略（OI-06）：会话消息 180 天、工具调用日志 180 天（脱敏后可延长）、
 * 已软删除宠物档案 30 天后物理清除、状态库<b>已覆盖</b>历史 180 天后物理清除。
 */
public interface DataRetentionService {

    /** 消息保留天数。 */
    int MESSAGE_RETENTION_DAYS = 180;
    /** 工具日志保留天数。 */
    int TOOL_LOG_RETENTION_DAYS = 180;
    /** 软删档案物理清除宽限期。 */
    int PET_SOFT_DELETE_GRACE_DAYS = 30;
    /**
     * 状态库「已覆盖历史」保留天数。
     *
     * <p><b>只清历史，不清生效事实</b>：{@code biz_memory_item} 的 ACTIVE 行是用户当前的
     * 个人知识，按留存策略自动消失会让管家「无故失忆」；其退出只有两条路径——
     * 被新事实覆盖（转 SUPERSEDED，本策略清理）或用户请求删除（FR-19 ②）。
     */
    int MEMORY_HISTORY_RETENTION_DAYS = 180;

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

    /**
     * 物理清理早于截止时间且<b>已被新事实覆盖</b>的状态库历史条目（W6 / FR-19 ①）。
     *
     * @param cutoff 截止时间；为 {@code null} 时不动库并返回 0
     * @return 删除行数
     */
    int purgeSupersededMemories(LocalDateTime cutoff);

    /** 执行全量定时清理（每日凌晨调用）。 */
    void purgeAll();
}
