package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.domain.port.VisionPort;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.domain.service.PetProfileService;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.RecognizeImageTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-14 贯通验证：图片识别经品种/昵称反查命中宠物档案（SRS FR-14 / BR-14）。
 *
 * <p>验收：当视觉返回包含已登记宠物品种「柯基」时，{@code recognize_image} 工具应经
 * {@link PetProfileService} 反查命中档案昵称「豆豆」，并在结果 {@code matched_profile} 中回填。
 * 同时覆盖：无匹配档案（matched_profile 为空）、模糊图（返回 EMPTY_RESULT 不虚构）。
 */
class RecognizeImageFr14IntegrationTest {

    private static final String OPENID = "openid-fr14";
    private static final ToolContext CTX = ToolContext.of("trace-fr14", OPENID, 1L, 1);

    @Test
    @DisplayName("FR-14：识别到已登记品种『柯基』→ 反查命中档案『豆豆』")
    void shouldMatchRegisteredProfileByBreed() {
        VisionPort vision = req -> new VisionResult("一只柯基犬，棕色，看起来很可爱", 0.92, null);
        PetProfileService profile = mock(PetProfileService.class);
        when(profile.listLive(ArgumentMatchers.any())).thenReturn(List.of(
                new PetProfileView(1L, OPENID, "豆豆", "狗", "柯基", "公",
                        null, null, "粘人", null, null, null, null)));
        ObjectProvider<PetProfileService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(profile);
        RecognizeImageTool tool = new RecognizeImageTool(vision, provider);

        ToolResult result = tool.execute(CTX, args("pet", "http://example.com/corgi.jpg"));

        assertThat(result.isSuccess()).as("高置信识别应成功").isTrue();
        JsonNode data = result.data();
        assertThat(data.get("description").asText()).contains("柯基");
        assertThat(data.get("matched_profile").asText())
                .as("应经档案反查命中昵称『豆豆』").isEqualTo("豆豆");
        assertThat(result.message()).contains("豆豆");
    }

    @Test
    @DisplayName("FR-14：识别到昵称『豆豆』（非品种字段）→ 仍以昵称命中")
    void shouldMatchRegisteredProfileByPetName() {
        VisionPort vision = req -> new VisionResult("这是豆豆，一只小狗", 0.88, null);
        PetProfileService profile = mock(PetProfileService.class);
        when(profile.listLive(ArgumentMatchers.any())).thenReturn(List.of(
                new PetProfileView(2L, OPENID, "豆豆", "狗", "柯基", "母",
                        null, null, null, null, null, null, null)));
        ObjectProvider<PetProfileService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(profile);
        RecognizeImageTool tool = new RecognizeImageTool(vision, provider);

        ToolResult result = tool.execute(CTX, args("pet", "http://example.com/pet.jpg"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("matched_profile").asText()).isEqualTo("豆豆");
    }

    @Test
    @DisplayName("FR-14：识别结果与档案无交集 → matched_profile 为空（不编造）")
    void shouldNotMatchWhenNoProfileOverlap() {
        VisionPort vision = req -> new VisionResult("一只橘猫，正在睡觉", 0.9, null);
        PetProfileService profile = mock(PetProfileService.class);
        when(profile.listLive(ArgumentMatchers.any())).thenReturn(List.of(
                new PetProfileView(3L, OPENID, "豆豆", "狗", "柯基", "公",
                        null, null, null, null, null, null, null)));
        ObjectProvider<PetProfileService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(profile);
        RecognizeImageTool tool = new RecognizeImageTool(vision, provider);

        ToolResult result = tool.execute(CTX, args("pet", "http://example.com/cat.jpg"));

        assertThat(result.isSuccess()).isTrue();
        JsonNode matched = result.data().get("matched_profile");
        assertThat(matched.isNull() || matched.asText().isEmpty())
                .as("无交集时不得虚构匹配").isTrue();
    }

    @Test
    @DisplayName("FR-14：模糊/极低置信图 → EMPTY_RESULT，不虚构（BR-09）")
    void shouldReturnEmptyResultWhenBlurry() {
        VisionPort vision = req -> new VisionResult("无法判断，可能是模糊的色块", 0.10, null);
        PetProfileService profile = mock(PetProfileService.class);
        when(profile.listLive(ArgumentMatchers.any())).thenReturn(List.of());
        ObjectProvider<PetProfileService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(profile);
        RecognizeImageTool tool = new RecognizeImageTool(vision, provider);

        ToolResult result = tool.execute(CTX, args("object", "http://example.com/blur.jpg"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("EMPTY_RESULT");
    }

    @Test
    @DisplayName("FR-14：未提供图片内容 → INVALID_ARGS")
    void shouldRejectMissingImage() {
        VisionPort vision = req -> new VisionResult("x", 1.0, null);
        PetProfileService profile = mock(PetProfileService.class);
        when(profile.listLive(ArgumentMatchers.any())).thenReturn(List.of());
        ObjectProvider<PetProfileService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(profile);
        RecognizeImageTool tool = new RecognizeImageTool(vision, provider);

        ToolResult result = tool.execute(CTX, args("pet", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    private static JsonNode args(String scene, String imageUrl) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("scene", scene);
        if (imageUrl != null) {
            node.put("image_url", imageUrl);
        }
        return node;
    }
}
