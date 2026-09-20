package com.lumensteward.clawbot.application.memory;

/**
 * 一轮对话活动（个人状态库生长管道的输入，迭代 4 W6 / §2.19）。
 *
 * <p>编排器在<b>成功产出终态回复后</b>发布本记录（复用其已有的 {@code ApplicationEventPublisher}，
 * 不新增构造器依赖），由 {@link MemoryGrowthListener} 异步转交抽取。
 * 与 {@code AnomalyNotice} / {@code OrchestrationTracedEvent} 同一套路。
 *
 * @param openid         用户标识（<b>原始值</b>，日志与落库侧脱敏，BR-21）
 * @param sessionId      会话 id（溯源用，可空）
 * @param traceId        链路标识（溯源用，可空）
 * @param userMessage    用户本轮原文
 * @param assistantReply 管家本轮终态回复
 */
public record MemoryGrowthNotice(String openid, Long sessionId, String traceId,
                                 String userMessage, String assistantReply) {

    /** 是否值得送抽取：两侧都有正文才有信息量（回复为空说明走了降级）。 */
    public boolean worthExtracting() {
        return openid != null && !openid.isBlank()
                && userMessage != null && !userMessage.isBlank()
                && assistantReply != null && !assistantReply.isBlank();
    }
}
