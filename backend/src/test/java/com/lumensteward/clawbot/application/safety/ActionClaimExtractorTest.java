package com.lumensteward.clawbot.application.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.safety.model.ActionClaim;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.ManagePetProfileTool;
import com.lumensteward.clawbot.domain.tool.impl.PlanRouteTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import com.lumensteward.clawbot.domain.tool.impl.RecognizeImageTool;
import com.lumensteward.clawbot.domain.tool.impl.SynthesizeVoiceTool;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作声明抽取器单测（SRS 9.4.5 + FR-23 零改动实证）。
 *
 * <p>迁移前本类内有一张按工具名硬编码的语义词表；迁移后关键词一律来自工具自述。
 * 因此这里承担两件事：<b>①迁移未漂移</b>（五个既有工具的语义与迁移前逐字一致），
 * <b>②新增工具零改本类</b>（注册表里多一个工具，抽取与匹配就自动认识它）。
 */
class ActionClaimExtractorTest {

    private final ActionClaimExtractor extractor =
            new ActionClaimExtractor(ToolRegistries.productionTools());

    /** 只实现契约基本方法的桩工具，用于验证"默认空关键词"分支。 */
    private record SilentTool() implements Tool {

        @Override
        public String name() {
            return "silent_tool";
        }

        @Override
        public String description() {
            return "未自述关键词的工具";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of("{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            return ToolResult.notExecuted("STUB", "不执行");
        }

        @Override
        public boolean idempotent() {
            return true;
        }

        @Override
        public boolean critical() {
            return false;
        }
    }

    @Test
    @DisplayName("迁移不漂移：五个既有工具的语义与硬编码时代逐字一致")
    void legacySemanticsPreserved() {
        assertThat(extractor.toolSemantics(ManagePetProfileTool.NAME))
                .containsExactlyInAnyOrder("记录", "保存", "更新", "删除", "档案", "登记");
        assertThat(extractor.toolSemantics(QueryExpressTool.NAME))
                .containsExactlyInAnyOrder("查询", "物流", "轨迹", "快递", "签收");
        assertThat(extractor.toolSemantics(PlanRouteTool.NAME))
                .containsExactlyInAnyOrder("规划", "路线", "导航", "全程");
        assertThat(extractor.toolSemantics(SynthesizeVoiceTool.NAME))
                .containsExactlyInAnyOrder("发送", "合成", "语音");
        assertThat(extractor.toolSemantics(RecognizeImageTool.NAME))
                .containsExactlyInAnyOrder("识别", "图片");
    }

    @Test
    @DisplayName("FR-23 零改本类：新注册的示例工具立刻可被抽取与匹配")
    void newToolNeedsNoExtractorChange() {
        List<ActionClaim> claims = extractor.extract("我已经为你查询了杭州的天气。");

        assertThat(claims).hasSize(1);
        ActionClaim claim = claims.get(0);
        assertThat(claim.keywords()).contains("查询");
        assertThat(extractor.toolSemantics(QueryWeatherTool.NAME)).contains("天气");
        // 既有工具不会被天气声明误配（关键词不相交）
        assertThat(claim.keywords()).doesNotContain("档案", "物流");
    }

    @Test
    @DisplayName("无完成态动词的回复不产生动作声明（保守，避免误判）")
    void plainQuestionProducesNoClaims() {
        assertThat(extractor.extract("明天天气怎么样？")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
    }

    @Test
    @DisplayName("未自述关键词的工具：语义为空集，只少一道校验不误判正常回复")
    void silentToolHasEmptySemantics() {
        ToolRegistry registry = new ToolRegistry(List.of(new SilentTool()));
        ActionClaimExtractor quiet = new ActionClaimExtractor(registry);

        assertThat(quiet.toolSemantics("silent_tool")).isEmpty();
        assertThat(quiet.toolSemantics("not_registered")).isEmpty();
        assertThat(quiet.extract("我已经保存好了。")).isNotEmpty();
        Set<String> universe = registry.claimKeywordUniverse();
        assertThat(universe).isEmpty();
    }
}
