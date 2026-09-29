package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.tool.impl.AskImageFollowupTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片追问工具测试（迭代 4 W11）。
 *
 * <p>覆盖 W10 机制的首个真实消费者语义：出现条件（缓存存在才可见）、执行返回缓存结论、
 * 缓存过期如实失败（BR-09 不编造）、与 recognize_image 的同语义判定词。
 */
class AskImageFollowupToolTest {

    private static final ToolContext CTX = ToolContext.of("trace-ask", "openid-ask", 1L, 1);
    private static final RecentImage CACHED = new RecentImage(
            "一只橘色的成年短毛猫，毛色干净，体态圆润", "pet", 0.9, Instant.now());

    /** 内存识图缓存桩：按 openid 存取。 */
    private static final class InMemoryRecentImageStore implements RecentImageStore {
        private final Map<String, RecentImage> images = new HashMap<>();

        @Override
        public boolean save(String openid, RecentImage image) {
            images.put(openid, image);
            return true;
        }

        @Override
        public Optional<RecentImage> find(String openid) {
            return Optional.ofNullable(images.get(openid));
        }
    }

    @Test
    @DisplayName("出现条件：仅当「缓存存在」的上下文 + 存储在场时可见（W10 契约的消费方）")
    void visibilityFollowsContextAndStore() {
        AskImageFollowupTool tool = new AskImageFollowupTool(new InMemoryRecentImageStore());

        assertThat(tool.visibleIn(new ToolVisibilityContext("openid-ask", true))).isTrue();
        assertThat(tool.visibleIn(new ToolVisibilityContext("openid-ask", false))).isFalse();
        assertThat(tool.visibleIn(null)).isFalse();
        // 存储缺失（standalone 构造）⇒ 恒不可见
        assertThat(new AskImageFollowupTool(null).visibleIn(new ToolVisibilityContext("openid-ask", true)))
                .isFalse();
    }

    @Test
    @DisplayName("执行：返回缓存的识图结论与新鲜度提示，无需重发图片")
    void returnsCachedDescriptionWithFreshnessNote() {
        InMemoryRecentImageStore store = new InMemoryRecentImageStore();
        store.save("openid-ask", CACHED);
        AskImageFollowupTool tool = new AskImageFollowupTool(store);

        ToolResult result = tool.execute(CTX, JsonUtils.mapper().createObjectNode());

        assertThat(result.isSuccess()).isTrue();
        JsonNode data = result.data();
        assertThat(data.get("description").asText()).isEqualTo(CACHED.description());
        assertThat(data.get("scene").asText()).isEqualTo("pet");
        assertThat(data.get("age_note").asText()).isEqualTo("刚刚识别");
        assertThat(result.message()).contains(CACHED.description()).contains("无需重发");
    }

    @Test
    @DisplayName("缓存已消失（下发可见、执行时过期）→ 结构化失败 CACHE_EXPIRED，不编造（BR-09）")
    void expiredCacheFailsHonestly() {
        AskImageFollowupTool tool = new AskImageFollowupTool(new InMemoryRecentImageStore());

        ToolResult result = tool.execute(CTX, JsonUtils.mapper().createObjectNode());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("CACHE_EXPIRED");
        assertThat(result.message()).contains("重新发送图片");
    }

    @Test
    @DisplayName("契约自述：判定词与 recognize_image 同语义（识别/图片），归因域独立")
    void contractMetadataIsSelfDescribed() {
        AskImageFollowupTool tool = new AskImageFollowupTool(new InMemoryRecentImageStore());

        assertThat(tool.name()).isEqualTo("ask_image");
        assertThat(tool.claimKeywords()).containsExactlyInAnyOrder("识别", "图片");
        assertThat(tool.monitorDomain()).isEqualTo("image_followup");
        assertThat(tool.readOnly()).isTrue();
        assertThat(tool.idempotent()).isTrue();
        assertThat(tool.critical()).isFalse();
        // 不参与话题切换判定（保守默认，未覆写 taskIntent）
        assertThat(tool.taskIntent()).isNull();
    }
}
