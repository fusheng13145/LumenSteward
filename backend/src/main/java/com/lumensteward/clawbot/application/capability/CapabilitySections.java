package com.lumensteward.clawbot.application.capability;

import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;

import java.util.List;
import java.util.Set;

/**
 * 能力自述段生成器（迭代 4 W12「能力清单真值化」/ 母本 M-2 §03-4 改造式吸收）。
 *
 * <p><b>要治的病：</b>静态提示词/欢迎语里硬编码"你拥有 X 能力"，会与实际可下发工具集漂移——
 * 灰度关闭的工具（如暗启动的 {@code query_weather}）、按上下文隐藏的工具（如 W11 的
 * {@code ask_image}）被静态文案声称，反而成为幻觉源（BR-04 同源）。
 *
 * <p><b>真值口径：</b>能力段一律由 {@link ToolRegistry} 按「禁用集合 + 可见性上下文」的
 * <b>实际可下发集</b>生成——提示词声称的、欢迎语承诺的、帮助指令列出的，与模型本轮真正
 * 看得到的函数 Schema 恒一致。
 *
 * <p>静态工具类（不进容器）：编排器、事件处理器各自持注册表直接调用，避免新增注入链。
 */
public final class CapabilitySections {

    private CapabilitySections() {
        // 静态工具，禁止实例化
    }

    /**
     * 面向系统提示词的能力段（紧凑、带约束语）。
     *
     * @param registry 工具注册表（可为 null，按"无工具"处理）
     * @param disabled 被禁用工具名集合（可为 null）
     * @param context  可见性上下文
     * @return 追加到系统提示词末尾的能力段
     */
    public static String forPrompt(ToolRegistry registry, Set<String> disabled,
                                   ToolVisibilityContext context) {
        List<Tool> tools = dispatchable(registry, disabled, context);
        if (tools.isEmpty()) {
            return "当前没有任何可调用的工具。涉及查询、操作类请求时，如实说明你无法执行，不要编造。";
        }
        StringBuilder sb = new StringBuilder("你当前实际可用的工具（只可调用以下这些，其余能力一律没有，不得声称可以调用）：\n");
        for (Tool tool : tools) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 面向用户的能力清单（欢迎语 / 「帮助」指令；编号列表，用工具自述的自然语言描述）。
     *
     * @param registry 工具注册表（可为 null）
     * @param disabled 被禁用工具名集合（可为 null）
     * @param context  可见性上下文（欢迎语场景用最小上下文，条件可见工具自然不出现）
     * @return 用户可读的能力清单
     */
    public static String forUser(ToolRegistry registry, Set<String> disabled,
                                 ToolVisibilityContext context) {
        List<Tool> tools = dispatchable(registry, disabled, context);
        if (tools.isEmpty()) {
            return "（当前暂无可自动执行的能力，欢迎直接和我聊天。）";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Tool tool : tools) {
            sb.append(i++).append(". ").append(tool.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static List<Tool> dispatchable(ToolRegistry registry, Set<String> disabled,
                                           ToolVisibilityContext context) {
        if (registry == null) {
            return List.of();
        }
        return registry.dispatchableSchemas(disabled, context).stream()
                .map(node -> node.path("function").path("name").asText())
                .map(name -> registry.find(name).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
