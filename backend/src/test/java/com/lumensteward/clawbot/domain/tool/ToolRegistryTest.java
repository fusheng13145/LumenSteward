package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具注册中心单测（SRS FR-23 验收①② + 异常流 1a/1b + 备选流 BR-32）。
 *
 * <p>FR-23 的两条硬判据在这里落地：
 * <ul>
 *   <li><b>零侵入</b>：示例插件 {@link QueryWeatherTool} 只靠注册即被下发、被反查，
 *       编排器/Prompt/看板/任务会话四处无一处改动；</li>
 *   <li><b>Fail-Fast</b>：非法 Schema、参数名重复、工具名冲突都在构造期抛出，
 *       且消息<b>指名道姓</b>（哪个工具、哪个参数、与谁冲突）。</li>
 * </ul>
 */
class ToolRegistryTest {

    private static final String VALID_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";

    /** 可控桩工具：只用于触发注册表校验分支。 */
    private record StubTool(String name, String schemaJson) implements Tool {

        @Override
        public String description() {
            return "桩工具";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of(schemaJson);
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            return ToolResult.notExecuted("STUB", "桩工具不执行");
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
    @DisplayName("FR-23 ①：新工具仅注册即被下发给模型，装配处零改动")
    void newToolIsPublishedOnceRegistered() {
        ToolRegistry registry = new ToolRegistry(List.of(new QueryWeatherTool()));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.names()).containsExactly(QueryWeatherTool.NAME);
        assertThat(registry.find(QueryWeatherTool.NAME)).isPresent();

        List<JsonNode> published = registry.enabledSchemas(Set.of());
        assertThat(published).hasSize(1);
        assertThat(published.get(0).path("type").asText()).isEqualTo("function");
        assertThat(published.get(0).path("function").path("name").asText()).isEqualTo(QueryWeatherTool.NAME);
        assertThat(published.get(0).path("function").path("parameters").path("required").get(0).asText())
                .isEqualTo("city");
    }

    @Test
    @DisplayName("FR-23：三处派生口径由工具自述并经注册表反查，未注册者走保守默认")
    void derivedMetadataIsReverseLookedUp() {
        ToolRegistry registry = new ToolRegistry(List.of(new QueryExpressTool(null), new QueryWeatherTool()));

        assertThat(registry.monitorDomainOf(QueryExpressTool.NAME)).isEqualTo("express");
        assertThat(registry.monitorDomainOf(QueryWeatherTool.NAME)).isEqualTo("weather");
        assertThat(registry.taskIntentOf(QueryExpressTool.NAME)).isEqualTo(IntentType.EXPRESS);
        assertThat(registry.claimKeywordsOf(QueryWeatherTool.NAME)).contains("天气");

        // 未注册（如已下线工具的历史日志）：归入 chat、无意图、无关键词——不凭空造事实
        assertThat(registry.monitorDomainOf("legacy_offline_tool")).isEqualTo("chat");
        assertThat(registry.taskIntentOf("legacy_offline_tool")).isNull();
        assertThat(registry.claimKeywordsOf("legacy_offline_tool")).isEmpty();
        assertThat(registry.claimKeywordsOf(null)).isEmpty();
    }

    @Test
    @DisplayName("BR-32：只实现契约基本方法的旧工具依然合法，且口径全部走保守默认")
    void minimalToolRemainsValidWithConservativeDefaults() {
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("legacy_tool", VALID_SCHEMA)));

        Tool tool = registry.find("legacy_tool").orElseThrow();
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.claimKeywords()).isEmpty();
        assertThat(tool.monitorDomain()).isEqualTo("chat");
        assertThat(tool.taskIntent()).isNull();
    }

    @Test
    @DisplayName("FR-23 ②：参数缺 type → 构造期失败并指明参数名")
    void missingPropertyTypeFailsFast() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new StubTool("bad_tool",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"description\":\"没有 type\"}}}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad_tool")
                .hasMessageContaining("参数 city 缺少 type");
    }

    @Test
    @DisplayName("异常流 1a：required 引用未声明参数 → 阻止启动")
    void requiredMustReferenceDeclaredParameter() {
        String bad = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                + "\"required\":[\"date\"]}";

        assertThatThrownBy(() -> new ToolRegistry(List.of(new StubTool("bad_tool", bad))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required 引用了未声明的参数: date");
    }

    @Test
    @DisplayName("异常流 1a：参数名重复 → 启动失败，不静默保留后者")
    void duplicateParameterNameFailsFast() {
        String bad = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},"
                + "\"city\":{\"type\":\"integer\"}}}";

        assertThatThrownBy(() -> new ToolRegistry(List.of(new StubTool("bad_tool", bad))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("参数名重复");
    }

    @Test
    @DisplayName("异常流 1a：根节点缺 type → 启动失败并说明缺什么")
    void missingRootTypeFailsFast() {
        String bad = "{\"properties\":{\"city\":{\"type\":\"string\"}}}";

        assertThatThrownBy(() -> new ToolRegistry(List.of(new StubTool("bad_tool", bad))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("根节点缺少 type");
    }

    @Test
    @DisplayName("异常流 1b：工具名冲突 → 启动失败并提示冲突对象")
    void duplicatedToolNameReportsBothImplementations() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                new StubTool(QueryWeatherTool.NAME, VALID_SCHEMA), new QueryWeatherTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具名冲突: " + QueryWeatherTool.NAME)
                .hasMessageContaining(StubTool.class.getName())
                .hasMessageContaining(QueryWeatherTool.class.getName());
    }

    @Test
    @DisplayName("FR-23：工具名不合规（大写/短名）→ 启动失败并给出命名约束")
    void illegalToolNameFailsFast() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new StubTool("QueryWeather", VALID_SCHEMA))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具名非法")
                .hasMessageContaining("QueryWeather");
    }

    @Test
    @DisplayName("FR-18 工具开关：被禁用的工具不下发，其余照常")
    void disabledToolsAreNotPublished() {
        ToolRegistry registry = ToolRegistries.productionTools();

        assertThat(registry.enabledSchemas(Set.of(QueryWeatherTool.NAME)))
                .extracting(node -> node.path("function").path("name").asText())
                .doesNotContain(QueryWeatherTool.NAME)
                .contains(QueryExpressTool.NAME);
        assertThat(registry.enabledSchemas(null)).hasSize(registry.size());
    }

    @Test
    @DisplayName("语义词表随注册自动扩展：抽取词汇表 == 各工具自述关键词并集")
    void claimKeywordUniverseIsDerivedFromTools() {
        ToolRegistry registry = ToolRegistries.productionTools();

        assertThat(registry.claimKeywordUniverse())
                .contains("天气", "气温", "预报")
                .contains("记录", "保存", "更新", "删除", "档案", "登记")
                .contains("查询", "物流", "轨迹", "快递", "签收")
                .contains("规划", "路线", "导航", "全程")
                .contains("发送", "合成", "语音")
                .contains("识别", "图片");
    }
}
