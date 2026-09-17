package com.lumensteward.clawbot.domain.context;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;

import java.util.List;

/**
 * 会话上下文存储端口（架构 5.2 / SRS FR-04，BR-07）。
 *
 * <p><b>BR-07 上下文隔离铁律：</b>所有读写以 {@code openid} 为唯一分区键（{@code conv:{openid}}），
 * 严禁跨用户复用。<b>Redis 故障时降级为无状态</b>（9.5）：{@link #isAvailable()} 返回 false，
 * 读取返回空、写入静默失败，主链路不中断。
 */
public interface ContextStore {

    /**
     * 载入上下文。
     *
     * @param openid 用户标识
     * @return 消息列表（不可用或为空时返回空列表）
     */
    List<ChatMessage> load(String openid);

    /**
     * 追加一条消息。
     *
     * @param openid  用户标识
     * @param message 消息
     */
    void append(String openid, ChatMessage message);

    /**
     * 批量追加消息。
     *
     * @param openid   用户标识
     * @param messages 消息列表
     */
    void appendAll(String openid, List<ChatMessage> messages);

    /**
     * 清空上下文。
     *
     * @param openid 用户标识
     */
    void clear(String openid);

    /**
     * 存储是否可用（Redis 故障 → false，上层走无状态降级）。
     *
     * @return 可用返回 true
     */
    boolean isAvailable();
}
