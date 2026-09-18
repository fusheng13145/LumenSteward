package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.domain.port.VisionPortException;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.client.LlmVisionAdapter;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LlmVisionAdapter} 单元测试（FR-10 / SRS）。
 *
 * <p>验证其作为领域端口实现，将 {@link LlmClient} 的异常转译为领域异常
 * {@link VisionPortException}，且不反向依赖基础设施异常类型。
 */
class LlmVisionAdapterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final LlmVisionAdapter adapter = new LlmVisionAdapter(llm);

    @Test
    @DisplayName("FR-10：视觉调用成功 → 透传 VisionResult")
    void shouldDelegateSuccess() throws VisionPortException {
        VisionRequest req = new VisionRequest(null, "http://x/y.jpg", "什么品种");
        when(llm.vision(req)).thenReturn(new VisionResult("一只柯基", 0.9, null));

        VisionResult result = adapter.vision(req);

        assertThat(result.description()).isEqualTo("一只柯基");
        assertThat(result.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("FR-10：LLM 异常 → 转译为 VisionPortException")
    void shouldTranslateLlmException() throws VisionPortException {
        VisionRequest req = new VisionRequest(null, "http://x/y.jpg", null);
        when(llm.vision(req)).thenThrow(new LlmUnavailableException("视觉不可用"));

        assertThatThrownBy(() -> adapter.vision(req))
                .isInstanceOf(VisionPortException.class)
                .hasMessageContaining("视觉能力暂不可用");
    }

    @Test
    @DisplayName("FR-10：缺少图片内容 → VisionPortException")
    void shouldRejectMissingImage() {
        VisionRequest req = new VisionRequest(null, null, null);

        assertThatThrownBy(() -> adapter.vision(req))
                .isInstanceOf(VisionPortException.class)
                .hasMessageContaining("缺少图片内容");
    }
}
