package com.lumensteward.clawbot.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * 编排（Agent Loop）配置（{@code orchestration.*}，G-16；架构 5.4）。
 *
 * <p>对应 SRS 中 SC-01（最大轮次）/ SC-02（最大并行工具数）/ SC-03（时间预算）/ FR-18（工具开关）。
 *
 * @param maxRounds         Agent Loop 最大轮次（SC-01）
 * @param maxParallelTools  单轮最大并行工具数（SC-02）
 * @param totalBudgetMs     单次链路总时间预算（ms，SC-03）
 * @param toolTimeoutMs     单工具执行超时（ms，SC-03）
 * @param maxRetry          单工具最大重试次数
 * @param disabledTools     被禁用的工具名集合（FR-18，为空表示不禁用）
 */
@ConfigurationProperties(prefix = "orchestration")
public record OrchestrationProperties(
        @DefaultValue("5") int maxRounds,
        @DefaultValue("3") int maxParallelTools,
        @DefaultValue("25000") int totalBudgetMs,
        @DefaultValue("8000") int toolTimeoutMs,
        @DefaultValue("1") int maxRetry,
        @DefaultValue Set<String> disabledTools) {
}
