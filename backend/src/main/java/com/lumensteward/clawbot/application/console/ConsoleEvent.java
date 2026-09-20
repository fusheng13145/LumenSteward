package com.lumensteward.clawbot.application.console;

import com.lumensteward.clawbot.common.util.MaskUtils;

/**
 * 控制台观测事件（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * <p>由 {@code AgentOrchestratorImpl} 经 {@code ApplicationEventPublisher} 发布，
 * {@code ConsoleEventRelay} 中继至 {@code ConsoleEventBuffer} 广播给活跃 SSE 连接。
 *
 * <p>openid 在构造时即脱敏（BR-21），事件对象全程不含明文用户标识；
 * {@code seq} 由缓冲在 {@link ConsoleEventBuffer#push(ConsoleEvent)} 时回填（全局单调）。
 */
public class ConsoleEvent {

    /** 事件类型。 */
    private final ConsoleEventType type;
    /** 全局单调自增序号（由缓冲在 push 时回填）。 */
    private long seq;
    /** 链路标识。 */
    private final String traceId;
    /** 会话 id（可空）。 */
    private final Long sessionId;
    /** 脱敏后的 openid（可空）。 */
    private final String openid;
    /** 工具名（可空）。 */
    private final String toolName;
    /** 链路内调用序号（可空）。 */
    private final Integer callSeq;
    /** Agent Loop 轮次（可空）。 */
    private final Integer round;
    /** 事件时间戳（epoch ms）。 */
    private final long ts;
    /** 可选载荷（JSON 文本；可空）。 */
    private final String payload;

    /**
     * 构造控制台事件。
     *
     * @param type       事件类型
     * @param traceId    链路标识
     * @param openid     用户标识（构造内脱敏）
     * @param sessionId  会话 id（可空）
     * @param round      Agent Loop 轮次（可空）
     * @param toolName   工具名（可空）
     * @param callSeq    链路内调用序号（可空）
     * @param payload    可选载荷 JSON（可空）
     */
    public ConsoleEvent(ConsoleEventType type, String traceId, String openid, Long sessionId,
                        Integer round, String toolName, Integer callSeq, String payload) {
        this.type = type;
        this.traceId = traceId;
        this.openid = MaskUtils.openid(openid);
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.callSeq = callSeq;
        this.round = round;
        this.ts = System.currentTimeMillis();
        this.payload = payload;
    }

    /** 回填全局序号（由缓冲在 push 时调用，返回自身以支持链式写）。 */
    public ConsoleEvent withSeq(long seq) {
        this.seq = seq;
        return this;
    }

    public ConsoleEventType getType() {
        return type;
    }

    public long getSeq() {
        return seq;
    }

    public String getTraceId() {
        return traceId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getOpenid() {
        return openid;
    }

    public String getToolName() {
        return toolName;
    }

    public Integer getCallSeq() {
        return callSeq;
    }

    public Integer getRound() {
        return round;
    }

    public long getTs() {
        return ts;
    }

    public String getPayload() {
        return payload;
    }
}
