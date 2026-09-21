package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.domain.intent.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册中心（架构 5.1 / SRS 9.4.2，启动 Fail-Fast）。
 *
 * <p>通过构造器注入 Spring 容器内<b>全部</b> {@link Tool} 实现（FR-23：新增工具零侵入）；构造期即
 * 完成 Schema 校验与工具名唯一性校验，任一不合法则抛出异常阻止应用启动——避免带缺陷工具上线后被
 * 模型触发（FR-23 异常流 1a）。
 *
 * <p><b>BR-04 关键约束：</b>物流/地图/语音等外部能力在 T03 仅以 SPI 客户端存在，Mock 适配器
 * <b>不得</b>实现 {@link Tool} 接口，因而<b>不会</b>被本注册中心收录、也不会下发给模型——从源头
 * 杜绝"模型编造可调用能力"。MVP 唯一注册工具为 {@code manage_pet_profile}（T04）。
 *
 * <p><b>派生口径的唯一反查入口（W3 / FR-23）：</b>{@link #claimKeywordsOf(String)} /
 * {@link #monitorDomainOf(String)} / {@link #taskIntentOf(String)} 把「工具名 → 一致性校验关键词 /
 * 看板归因域 / 任务意图」的映射留在工具自身，消费方<b>只</b>经本注册表反查。由此新增工具时，
 * 对话引擎、安全校验、监控看板、任务会话四处均无需改动，插件化才是真的（而非只在注册处开放）。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具名约束：小写字母、数字与下划线（与附录 B 契约一致）。 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 构造器注入全部工具实现。
     *
     * @param toolList Spring 容器内的 {@link Tool} 列表（可为空）
     */
    public ToolRegistry(List<Tool> toolList) {
        List<Tool> candidates = toolList == null ? List.of() : toolList;
        for (Tool tool : candidates) {
            validate(tool);
            Tool previous = tools.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名冲突: " + tool.name() + " 已由 "
                        + previous.getClass().getName() + " 注册，冲突者 " + tool.getClass().getName());
            }
        }
        log.info("ToolRegistry 初始化完成，已注册工具数={} names={}", tools.size(), tools.keySet());
    }

    /**
     * 校验单个工具（Schema 非法 / 名非法 → 抛异常，阻止启动）。
     *
     * @param tool 工具实例
     */
    private void validate(Tool tool) {
        if (tool == null) {
            throw new IllegalStateException("工具实例为空，无法注册");
        }
        String name = tool.name();
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalStateException("工具名非法（须匹配 " + NAME_PATTERN.pattern() + "）: " + name
                    + "，实现类 " + tool.getClass().getName());
        }
        JsonSchema schema = tool.parametersSchema();
        if (schema == null) {
            throw new IllegalStateException("工具 " + name + " 未提供参数 Schema，启动终止");
        }
        List<String> violations = schema.structuralViolations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("工具参数 Schema 非法，启动终止: " + name + "（"
                    + tool.getClass().getName() + "）→ " + String.join("；", violations));
        }
    }

    /**
     * 按名查找工具。
     *
     * @param name 工具名
     * @return 命中的工具；未注册返回 {@link Optional#empty()}
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 工具自述的动作声明关键词（FR-09 一致性校验口径，FR-23 要求由工具提供）。
     *
     * @param toolName 工具名（可空）
     * @return 关键词集合；未注册或未声明返回空集（漏声明只少一道校验，不误判正常回复）
     */
    public Set<String> claimKeywordsOf(String toolName) {
        return find(toolName).map(Tool::claimKeywords).orElse(Set.of());
    }

    /** 已注册工具声明的关键词并集（动作声明抽取的语义词汇表，随工具注册自动扩展）。 */
    public Set<String> claimKeywordUniverse() {
        Set<String> universe = new LinkedHashSet<>();
        for (Tool tool : tools.values()) {
            universe.addAll(tool.claimKeywords());
        }
        return Collections.unmodifiableSet(universe);
    }

    /**
     * 工具自述的看板归因域（FR-17 意图分布口径）。
     *
     * @param toolName 工具名（可空）
     * @return 归因域标签；未注册或未声明返回 {@code chat}（不凭空造域）
     */
    public String monitorDomainOf(String toolName) {
        return find(toolName).map(Tool::monitorDomain).orElse("chat");
    }

    /**
     * 工具自述的意图（FR-24 话题切换判定）。
     *
     * @param toolName 工具名（可空）
     * @return 意图类型；未注册或未声明返回 null（不参与话题切换判定）
     */
    public IntentType taskIntentOf(String toolName) {
        return find(toolName).map(Tool::taskIntent).orElse(null);
    }

    /** 已注册的全部工具名（不可变视图）。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /** 已注册工具数量。 */
    public int size() {
        return tools.size();
    }

    /**
     * 过滤被禁用工具后，生成可下发 LLM 的 OpenAI 工具数组（FR-18 工具开关 / SRS 9.4.2）。
     *
     * @param disabledTools 被禁用的工具名集合（可为 null）
     * @return 形如 {@code [{"type":"function","function":{...}}]} 的节点列表
     */
    public List<JsonNode> enabledSchemas(Set<String> disabledTools) {
        Set<String> disabled = disabledTools == null ? Set.of() : disabledTools;
        return tools.values().stream()
                .filter(tool -> !disabled.contains(tool.name()))
                .map(ToolRegistry::toOpenAiToolNode)
                .toList();
    }

    private static JsonNode toOpenAiToolNode(Tool tool) {
        ObjectNode function = JsonNodeFactory.instance.objectNode();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", tool.parametersSchema().node());

        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("type", "function");
        envelope.set("function", function);
        return envelope;
    }
}
