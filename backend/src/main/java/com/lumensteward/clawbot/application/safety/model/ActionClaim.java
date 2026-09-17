package com.lumensteward.clawbot.application.safety.model;

import java.util.List;
import java.util.Set;

/**
 * 动作声明（架构 5.2 / SRS 9.4.5 步骤 1）。
 *
 * @param text     声明原文（子句）
 * @param keywords 语义关键词集合（用于与工具语义求交）
 * @param numerics 声明中出现的数值/日期字面量（用于数值可回溯校验）
 */
public record ActionClaim(String text, Set<String> keywords, List<String> numerics) {

    public ActionClaim {
        keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        numerics = numerics == null ? List.of() : List.copyOf(numerics);
    }

    /** 是否含可回溯性要求（数值或实体）。 */
    public boolean hasNumerics() {
        return !numerics.isEmpty();
    }
}
