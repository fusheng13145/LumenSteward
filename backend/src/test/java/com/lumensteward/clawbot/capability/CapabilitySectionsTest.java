package com.lumensteward.clawbot.capability;

import com.lumensteward.clawbot.application.capability.CapabilitySections;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import com.lumensteward.clawbot.domain.tool.impl.AskImageFollowupTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力自述段真值化测试（迭代 4 W12）。
 *
 * <p>核心判据：提示词/欢迎语声称的能力集合 ≡ 实际可下发工具集——禁用的（灰度关闭）与
 * 条件不可见的（W11 ask_image 无缓存）都不得出现在文案里；空集时如实说明"没有"。
 */
class CapabilitySectionsTest {

    private static final ToolVisibilityContext MIN = ToolVisibilityContext.of(null);

    @Test
    @DisplayName("forPrompt：能力段 = 实际下发集，逐工具 name + 自述")
    void promptSectionMatchesDispatchableSet() {
        ToolRegistry registry = new ToolRegistry(List.of(new QueryExpressTool(null), new QueryWeatherTool()));

        String section = CapabilitySections.forPrompt(registry, Set.of(), MIN);

        assertThat(section).contains("只可调用以下这些").contains("query_express").contains("query_weather");
        // 禁用（灰度关闭）的工具不出现——静态文案声称被关能力即幻觉源
        assertThat(CapabilitySections.forPrompt(registry, Set.of(QueryWeatherTool.NAME), MIN))
                .doesNotContain("query_weather")
                .contains("query_express");
    }

    @Test
    @DisplayName("条件可见工具随上下文出现/消失（W11 ask_image 与欢迎语/帮助口径一致）")
    void conditionalToolFollowsVisibilityContext() {
        ToolRegistry withStore = new ToolRegistry(List.of(
                new AskImageFollowupTool(new InMemoryImageStore()), new QueryExpressTool(null)));
        ToolRegistry withoutStore = new ToolRegistry(List.of(
                new AskImageFollowupTool(null), new QueryExpressTool(null)));

        // 无缓存（最小上下文）→ ask_image 不进清单；有缓存 → 出现
        assertThat(CapabilitySections.forUser(withStore, Set.of(), MIN))
                .doesNotContain("重发图片");
        assertThat(CapabilitySections.forUser(withStore, Set.of(), new ToolVisibilityContext("o", true)))
                .contains("重发图片");
        // 存储缺失（standalone）→ 恒不可见，即使上下文声称有缓存
        assertThat(CapabilitySections.forUser(withoutStore, Set.of(), new ToolVisibilityContext("o", true)))
                .doesNotContain("重发图片");
    }

    /** 内存识图缓存桩（有存货 ⇒ 上下文为真时 ask_image 可见）。 */
    private static final class InMemoryImageStore implements RecentImageStore {
        @Override
        public boolean save(String openid, RecentImage image) {
            return true;
        }

        @Override
        public java.util.Optional<RecentImage> find(String openid) {
            return java.util.Optional.of(new RecentImage("一只橘猫", "pet", 0.9, java.time.Instant.now()));
        }
    }

    @Test
    @DisplayName("forUser：空集如实说明，不凭空列能力")
    void emptySetSaysSoHonestly() {
        assertThat(CapabilitySections.forUser(null, Set.of(), MIN)).contains("暂无可自动执行的能力");
        ToolRegistry registry = new ToolRegistry(List.of(new QueryExpressTool(null)));
        assertThat(CapabilitySections.forUser(registry, Set.of(QueryExpressTool.NAME), MIN))
                .contains("暂无可自动执行的能力");
    }

    @Test
    @DisplayName("生产工具全集：暗启动口径下帮助清单不含 query_weather（默认禁用）")
    void productionSetHonorsDisabledTools() {
        ToolRegistry registry = ToolRegistries.productionTools();

        String help = CapabilitySections.forUser(registry, Set.of("query_weather"), MIN);
        assertThat(help).doesNotContain("查不到").doesNotContain("城市天气");
        assertThat(help).contains("宠物档案").contains("快递");
    }
}
