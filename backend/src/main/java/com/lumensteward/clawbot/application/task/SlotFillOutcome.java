package com.lumensteward.clawbot.application.task;

import com.lumensteward.clawbot.domain.model.TaskContext;

/**
 * 槽位填充结果（SRS FR-24 基本流 / BR-33）。
 *
 * @param status    处理结果状态
 * @param context   受影响的任务上下文（{@link Status#NO_TASK} 时为 null）
 * @param replyText 面向用户的文字（追问 / 放弃说明 / 话题切换说明；无则 null）
 */
public record SlotFillOutcome(Status status, TaskContext context, String replyText) {

    /**
     * 填充结果状态。
     */
    public enum Status {
        /** 无活跃任务，走普通编排。 */
        NO_TASK,
        /** 槽位已填但尚未齐备：继续追问。 */
        SLOT_FILLED_INCOMPLETE,
        /** 槽位已齐备：可续接执行原任务（调用 {@code resume}）。 */
        SLOT_FILLED_COMPLETE,
        /** 本次输入无效：在阈值内，重新追问。 */
        INVALID_REPROMPT,
        /** 连续无效达阈值：放弃任务并给出明确说明（BR-33，不出现下一次追问）。 */
        ABANDONED,
        /** 话题明显切换：中断并清理任务。 */
        TOPIC_SWITCH
    }

    /**
     * 无任务结果。
     *
     * @return NO_TASK 结果
     */
    public static SlotFillOutcome noTask() {
        return new SlotFillOutcome(Status.NO_TASK, null, null);
    }

    /**
     * 槽位未齐备，继续追问。
     *
     * @param context   任务上下文
     * @param replyText 追问文案
     * @return 结果
     */
    public static SlotFillOutcome incomplete(TaskContext context, String replyText) {
        return new SlotFillOutcome(Status.SLOT_FILLED_INCOMPLETE, context, replyText);
    }

    /**
     * 槽位齐备，可续接执行。
     *
     * @param context 任务上下文
     * @return 结果
     */
    public static SlotFillOutcome complete(TaskContext context) {
        return new SlotFillOutcome(Status.SLOT_FILLED_COMPLETE, context, null);
    }

    /**
     * 无效输入，重新追问。
     *
     * @param context   任务上下文
     * @param replyText 追问文案
     * @return 结果
     */
    public static SlotFillOutcome invalid(TaskContext context, String replyText) {
        return new SlotFillOutcome(Status.INVALID_REPROMPT, context, replyText);
    }

    /**
     * 放弃任务。
     *
     * @param context   任务上下文
     * @param replyText 明确说明文案
     * @return 结果
     */
    public static SlotFillOutcome abandoned(TaskContext context, String replyText) {
        return new SlotFillOutcome(Status.ABANDONED, context, replyText);
    }

    /**
     * 话题切换。
     *
     * @param context   被中断的任务上下文
     * @param replyText 说明文案
     * @return 结果
     */
    public static SlotFillOutcome topicSwitch(TaskContext context, String replyText) {
        return new SlotFillOutcome(Status.TOPIC_SWITCH, context, replyText);
    }
}
