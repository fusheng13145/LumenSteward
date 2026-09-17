package com.lumensteward.clawbot.infrastructure.cache;

/**
 * 消息幂等去重（架构 5.4 / SRS 9.4.6，L1 接入层）。
 *
 * <p>以 {@code SETNX dedup:msg:{msgId}}（TTL 300s）实现（8.3）。同一 MsgId 首次返回 true，
 * 重复返回 false，从而实现「同 MsgId 10 次业务处理 1 次」（AC-A3）。
 */
public interface DedupService {

    /**
     * 标记消息（若此前不存在）。
     *
     * @param msgId 平台消息 id
     * @return true 表示首次出现（可继续业务）；false 表示重复（应幂等丢弃）
     */
    boolean markIfAbsent(String msgId);
}
