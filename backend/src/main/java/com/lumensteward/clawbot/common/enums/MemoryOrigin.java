package com.lumensteward.clawbot.common.enums;

/**
 * 个人状态库条目来源方式（迭代 4 W6 / 溯源）。
 *
 * <p>枚举名即落库值（{@code biz_memory_item.origin}）。与 {@code extractor}（哪个抽取器）
 * 共同构成写入溯源。
 */
public enum MemoryOrigin {

    /** 自动生长：链路结束后由 LLM 从对话中抽取（W6 唯一已启用路径）。 */
    AUTO_EXTRACT,

    /** 工具写入：{@code remember} 等垂直动词工具直写。<b>预留未启用</b>（属 W8 主动执行层）。 */
    TOOL
}
