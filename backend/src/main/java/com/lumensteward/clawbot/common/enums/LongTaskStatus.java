package com.lumensteward.clawbot.common.enums;

/**
 * 长任务状态（迭代 4 W15）。
 *
 * <p>状态机：{@code PENDING → RUNNING → SUCCEEDED | FAILED}。RUNNING 在进程重启后
 * 由续跑逻辑统一重置回 PENDING（中断残局），因此 RUNNING 不会跨进程存活。
 * 投递由 {@code delivered_at} 幂等闩单独控制，与执行状态解耦——「执行成功但投递前崩溃」
 * 的任务恢复侧只补投、不再执行。
 */
public enum LongTaskStatus {

    /** 待执行（含重启续跑重置回队列的残局）。 */
    PENDING,

    /** 执行中（进程内状态，不跨重启存活）。 */
    RUNNING,

    /** 执行成功（结果已落 result_json）。 */
    SUCCEEDED,

    /** 执行失败（error_type 分类：TIMEOUT / NO_HANDLER / HANDLER_FAILED）。 */
    FAILED
}
