package com.lumensteward.clawbot.application.retention;

/**
 * 用户数据删除服务（FR-19 ②）。
 *
 * <p>响应「删除我的数据」请求：按范围清理 PII，账户锚点匿名化，工具日志 openid 匿名化，
 * 全程留审计。删除后数据库中不应再存在可关联到该用户的原始个人信息（BR-27/28）。
 */
public interface UserDataDeletionService {

    /**
     * 删除指定用户的个人数据。
     *
     * @param openid      用户标识
     * @param operatorId 操作人（管理端为管理员 id；对话触发为 null）
     * @param ip          来源 IP
     * @param scope       删除范围
     * @return 删除结果摘要（各表影响行数）
     */
    DeletionSummary deleteUserData(String openid, Long operatorId, String ip, DeletionScope scope);

    /**
     * 删除结果摘要。
     *
     * @param openid         原用户标识（已匿名化前的入参）
     * @param scope          范围
     * @param messages       删除消息数
     * @param sessions       删除会话数
     * @param pets           删除档案数
     * @param memories       删除状态库条目数（W6：含生效与已覆盖历史，随对话数据一并删除）
     * @param anonymizedLogs 匿名化工具日志数
     * @param anonymizedUser 是否匿名化账户锚点
     */
    record DeletionSummary(String openid, DeletionScope scope, int messages, int sessions,
                           int pets, int memories, int anonymizedLogs, boolean anonymizedUser) {
    }
}
