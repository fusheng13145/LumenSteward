package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.domain.memory.MemoryWrite;

import java.util.List;

/**
 * 记忆抽取端口（迭代 4 W6：把一轮对话变成若干候选事实）。
 *
 * <p>生长式而非人工灌库（§2.19）：抽取的输入<b>只有对话本身</b>，不引入外部知识库。
 * 实现须<b>永不抛出</b>——失败即返回空列表，由监听器按 best-effort 处理。
 */
public interface MemoryExtractor {

    /**
     * 从一轮对话中抽取候选事实。
     *
     * @param notice   对话活动
     * @param maxItems 条数上限（调用方按运行时配置给定，实现侧须夹紧）
     * @return 候选写入意图（可能为空）；抽取失败或无合格事实时为空列表
     */
    List<MemoryWrite> extract(MemoryGrowthNotice notice, int maxItems);
}
