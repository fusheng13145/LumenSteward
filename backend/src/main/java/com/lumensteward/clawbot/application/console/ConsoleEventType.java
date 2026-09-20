package com.lumensteward.clawbot.application.console;

/**
 * 控制台事件类型（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * <ul>
 *   <li>{@code TOOL_START} / {@code TOOL_END}：工具调用起止（与 log_tool_call 同刻，观测耗时偏差 &lt;200ms）；</li>
 *   <li>{@code MESSAGE_DELTA}：本轮终态回复（本迭代一次性下发，非逐 token 流式）；</li>
 *   <li>{@code ERROR}：链路级错误（预留）；</li>
 *   <li>{@code DONE}：编排结束（成功或降级均发送）。</li>
 * </ul>
 */
public enum ConsoleEventType {
    /** 工具调用开始 */
    TOOL_START,
    /** 工具调用结束 */
    TOOL_END,
    /** 终态回复（一次性下发） */
    MESSAGE_DELTA,
    /** 链路级错误（预留） */
    ERROR,
    /** 编排结束 */
    DONE
}
