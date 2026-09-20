package com.lumensteward.clawbot.interfaces.dto.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lumensteward.clawbot.application.console.ConsoleEvent;

/**
 * 控制台事件序列化视图（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * <p>字段与前端 {@code utils/sse.ts} 解析一一对应；{@code id} 对应事件全局序号 {@code seq}，
 * 与 SSE {@code id} 字段一致，支撑 {@code Last-Event-ID} 续传。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleEventVO(
        /** 全局序号（与 SSE id 一致，支撑 Last-Event-ID 续传） */
        long id,
        /** 事件类型 */
        String type,
        /** 链路标识 */
        String traceId,
        /** 会话 id（可空） */
        Long sessionId,
        /** 脱敏后的 openid（可空） */
        String openid,
        /** 工具名（可空） */
        String toolName,
        /** 链路内调用序号（可空） */
        Integer callSeq,
        /** Agent Loop 轮次（可空） */
        Integer round,
        /** 事件时间戳（epoch ms） */
        long ts,
        /** 可选载荷 JSON（可空） */
        String payload) {

    /**
     * 由领域事件构造视图。
     *
     * @param event 控制台事件
     * @return 视图
     */
    public static ConsoleEventVO from(ConsoleEvent event) {
        return new ConsoleEventVO(event.getSeq(), event.getType().name(), event.getTraceId(),
                event.getSessionId(), event.getOpenid(), event.getToolName(),
                event.getCallSeq(), event.getRound(), event.getTs(), event.getPayload());
    }
}
