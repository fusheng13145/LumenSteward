package com.lumensteward.clawbot.application.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 槽位填充器端口（SRS FR-24：优先槽位填充而非全量意图重识别）。
 *
 * <p>用户追问后的补充消息<b>先</b>经由本端口尝试填充当前任务的待填槽位；命中即跳过全量意图识别，
 * 直接续接原任务，降低时延与误判（与 FR-05 的低置信度追问互补）。
 */
@FunctionalInterface
public interface SlotFiller {

    /**
     * 尝试从用户消息中抽取待填槽位。
     *
     * @param pendingSlots 当前待填槽位（保持顺序，取首个可抽取者）
     * @param userMessage  用户消息
     * @return 抽取到的「槽位名 → 值」；无法抽取返回 {@link Optional#empty()}
     */
    Optional<Map<String, String>> fill(List<String> pendingSlots, String userMessage);
}
