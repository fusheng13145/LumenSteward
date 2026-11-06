package com.lumensteward.clawbot.common.enums;

/**
 * 承诺状态（迭代 4 W7/W8 最小提醒切片）。
 *
 * <p>状态机：{@code PENDING → DELIVERED}（到点投递，{@code delivered_at} 幂等闩保证至多一次）。
 * 取消 / 续期 / 转办属 W8 全动词集（D3），本切片刻意不引入——没有取消路径是已知边界
 * （手册 §7.21 口径），到期即投、投完即终态。
 */
public enum CommitmentStatus {

    /** 待投递（未到期或预算受限暂缓，调度器按 due_at 到点扫描）。 */
    PENDING,

    /** 已投递（终态；delivered_at 非空）。 */
    DELIVERED
}
