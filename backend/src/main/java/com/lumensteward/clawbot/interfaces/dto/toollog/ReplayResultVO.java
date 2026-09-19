package com.lumensteward.clawbot.interfaces.dto.toollog;

import java.util.List;

/**
 * 工具调用回放结果视图（A-2 / 迭代 2 T12）。
 *
 * @param traceId     链路标识
 * @param openid      用户（已脱敏，G-11）
 * @param dryRun      是否干跑
 * @param items       逐条回放项
 * @param consistency 一致性复现结果（未给回复文本时 replayed=false）
 */
public record ReplayResultVO(String traceId,
                             String openid,
                             boolean dryRun,
                             List<ReplayItemVO> items,
                             ReplayConsistencyVO consistency) {

    /**
     * 单条回放项。
     *
     * @param logId          历史日志主键
     * @param toolName       工具名
     * @param paramsJson     历史入参
     * @param originalStatus 历史状态
     * @param replayStatus   本次回放状态
     * @param errorType      异常分类
     * @param message        说明
     * @param dataJson       本次结果
     * @param latencyMs      本次耗时
     * @param skipped        是否被跳过
     * @param skipReason     跳过原因
     */
    public record ReplayItemVO(Long logId, String toolName, String paramsJson, String originalStatus,
                               String replayStatus, String errorType, String message, String dataJson,
                               long latencyMs, boolean skipped, String skipReason) {
    }

    /**
     * 一致性复现结果。
     *
     * @param replayed    是否执行复现
     * @param intercepted 是否复现出拦截
     * @param claimText   命中问题的声明原文
     * @param reason      判定原因
     */
    public record ReplayConsistencyVO(boolean replayed, boolean intercepted, String claimText,
                                      String reason) {
    }
}
