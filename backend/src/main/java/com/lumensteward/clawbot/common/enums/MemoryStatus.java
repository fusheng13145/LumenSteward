package com.lumensteward.clawbot.common.enums;

/**
 * 个人状态库条目状态（迭代 4 W6）。
 *
 * <p>枚举名即落库值（{@code biz_memory_item.status}）。同名事实被新事实覆盖时，旧行<b>不删除</b>
 * 而是转 {@link #SUPERSEDED}，使「管家为什么这么认为」可回看；仅 {@link #ACTIVE} 参与召回。
 */
public enum MemoryStatus {

    /** 生效中：参与召回，且同 (openid, kind, name) 至多一条。 */
    ACTIVE,

    /** 已被覆盖：不再召回，保留为历史，超期由留存任务物理清理。 */
    SUPERSEDED
}
