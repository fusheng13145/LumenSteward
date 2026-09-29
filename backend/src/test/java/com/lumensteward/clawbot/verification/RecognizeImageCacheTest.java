package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.port.VisionPort;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.RecognizeImageTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 识图缓存写入测试（迭代 4 W11）。
 *
 * <p>验证「识图成功 → 按 openid 缓存结论」这一写入侧语义：
 * <ul>
 *   <li>识别成功（非模糊）写缓存，覆盖写 = 换图即换缓存；</li>
 *   <li>模糊图（EMPTY_RESULT）不缓存——没有可信结论可续接；</li>
 *   <li>缓存写入失败只降级追问能力，识别回复照常（fail-open）。</li>
 * </ul>
 */
class RecognizeImageCacheTest {

    private static final ToolContext CTX = ToolContext.of("trace-cache", "openid-cache", 1L, 1);

    private static JsonNode args() {
        return com.lumensteward.clawbot.common.util.JsonUtils.mapper().createObjectNode()
                .put("scene", "pet")
                .put("image_url", "http://example.com/cat.jpg");
    }

    @Test
    @DisplayName("识别成功 → 写缓存；再次识别覆盖（换图即换缓存）")
    void successWritesAndOverwritesCache() {
        VisionPort vision = req -> new VisionResult("一只橘色的成年短毛猫", 0.9, null);
        RecentImageStore store = mock(RecentImageStore.class);
        when(store.save(anyString(), any())).thenReturn(true);
        RecognizeImageTool tool = new RecognizeImageTool(vision, null, store);

        ToolResult first = tool.execute(CTX, args());
        ToolResult second = tool.execute(CTX, args());

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        ArgumentCaptor<RecentImage> captor = ArgumentCaptor.forClass(RecentImage.class);
        verify(store, times(2)).save(eq("openid-cache"), captor.capture());
        assertThat(captor.getValue().description()).isEqualTo("一只橘色的成年短毛猫");
        assertThat(captor.getValue().scene()).isEqualTo("pet");
    }

    @Test
    @DisplayName("模糊图（低于阈值 → EMPTY_RESULT）不写缓存：没有可信结论可续接")
    void blurryResultDoesNotCache() {
        VisionPort vision = req -> new VisionResult("模糊不清", 0.1, null);
        RecentImageStore store = mock(RecentImageStore.class);
        RecognizeImageTool tool = new RecognizeImageTool(vision, null, store);

        ToolResult result = tool.execute(CTX, args());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("EMPTY_RESULT");
        verify(store, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("缓存写入失败 → 识别回复照常成功（fail-open，只降级追问能力）")
    void storeFailureDoesNotBreakRecognition() {
        VisionPort vision = req -> new VisionResult("一只橘色的成年短毛猫", 0.9, null);
        RecentImageStore store = mock(RecentImageStore.class);
        when(store.save(anyString(), any())).thenReturn(false);
        RecognizeImageTool tool = new RecognizeImageTool(vision, null, store);

        ToolResult result = tool.execute(CTX, args());

        assertThat(result.isSuccess()).as("存储故障不得影响识别回复").isTrue();
        assertThat(result.data().get("description").asText()).contains("短毛猫");
    }
}
