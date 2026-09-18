package com.lumensteward.clawbot.application.safety;

import com.lumensteward.clawbot.application.safety.model.ActionClaim;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作声明抽取器（架构 5.2 / SRS 9.4.5 步骤 1）。
 *
 * <p>基于<b>保守的完成态动词词表</b> + 工具语义词表，从回复中抽取"已执行动作"的声明。词表刻意只
 * 收录明确的完成态动词，以降低误判（9.4.5 误判风险评估缓解措施 1）。
 *
 * <p>工具语义词表（tool semantics）既用于抽取，也用于一致性校验的支持匹配：
 * <pre>
 *   manage_pet_profile → {记录,保存,更新,删除,档案,登记}
 *   query_express      → {查询,物流,轨迹,快递,签收}
 *   plan_route         → {规划,路线,导航,全程}
 *   synthesize_voice   → {发送,合成,语音}
 *   recognize_image    → {识别,图片}
 * </pre>
 */
@Component
public class ActionClaimExtractor {

    /** 完成态动作动词（"已(经)(为你|帮你)?&lt;verb&gt;"）。 */
    private static final Pattern COMPLETION_PATTERN = Pattern.compile(
            "已(?:经)?(?:为您|帮你|给你|为你)?(查询|查到|查找|获取|规划|保存|记录|记下|更新|修改|删除|删掉|登记|发送|合成|生成|找到|定位|设置|添加|新增|识别)");

    /** 子句切分（句末标点或换行）。 */
    private static final Pattern SENTENCE_SPLITTER = Pattern.compile("[。！？!?\\n\\r]+");

    /** 数值/日期字面量（含小数、百分号、日期）。 */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?%?|\\d{4}-\\d{2}-\\d{2}");

    /** 动词 → 语义关键词。 */
    private static final Map<String, String> VERB_KEYWORD = Map.ofEntries(
            Map.entry("查询", "查询"), Map.entry("查到", "查询"), Map.entry("查找", "查询"),
            Map.entry("获取", "查询"), Map.entry("找到", "查询"), Map.entry("定位", "定位"),
            Map.entry("规划", "规划"),
            Map.entry("保存", "保存"),
            Map.entry("记录", "记录"), Map.entry("记下", "记录"),
            Map.entry("更新", "更新"), Map.entry("修改", "更新"), Map.entry("设置", "更新"),
            Map.entry("删除", "删除"), Map.entry("删掉", "删除"),
            Map.entry("登记", "登记"), Map.entry("添加", "记录"), Map.entry("新增", "记录"),
            Map.entry("发送", "发送"), Map.entry("合成", "合成"), Map.entry("生成", "合成"),
            Map.entry("识别", "识别"));

    /** 全部语义关键词（用于从子句中抽取存在的语义词）。 */
    private static final Set<String> SEMANTIC_KEYWORDS = Set.of(
            "记录", "保存", "更新", "删除", "档案", "登记",
            "查询", "物流", "轨迹", "快递", "签收",
            "规划", "路线", "导航", "全程",
            "发送", "合成", "语音",
            "识别", "图片");

    /**
     * 抽取动作声明。
     *
     * @param reply 模型回复
     * @return 动作声明列表（无声明返回空列表）
     */
    public List<ActionClaim> extract(String reply) {
        List<ActionClaim> claims = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return claims;
        }
        for (String sentence : SENTENCE_SPLITTER.split(reply)) {
            String clause = sentence.trim();
            if (clause.isEmpty()) {
                continue;
            }
            Matcher matcher = COMPLETION_PATTERN.matcher(clause);
            Set<String> keywords = new LinkedHashSet<>();
            boolean matched = false;
            while (matcher.find()) {
                matched = true;
                String verb = matcher.group(1);
                String keyword = VERB_KEYWORD.get(verb);
                if (keyword != null) {
                    keywords.add(keyword);
                }
            }
            if (!matched) {
                continue;
            }
            // 补充子句中出现的语义关键词（提升与工具语义的匹配准确度）
            for (String semantic : SEMANTIC_KEYWORDS) {
                if (clause.contains(semantic)) {
                    keywords.add(semantic);
                }
            }
            claims.add(new ActionClaim(clause, keywords, extractNumerics(clause)));
        }
        return claims;
    }

    /**
     * 工具语义映射（供一致性校验使用）。
     *
     * @param toolName 工具名
     * @return 该工具对应的语义关键词集合
     */
    public Set<String> toolSemantics(String toolName) {
        if (toolName == null) {
            return Set.of();
        }
        return switch (toolName) {
            case "manage_pet_profile" -> Set.of("记录", "保存", "更新", "删除", "档案", "登记");
            case "query_express" -> Set.of("查询", "物流", "轨迹", "快递", "签收");
            case "plan_route" -> Set.of("规划", "路线", "导航", "全程");
            case "synthesize_voice" -> Set.of("发送", "合成", "语音");
            case "recognize_image" -> Set.of("识别", "图片");
            default -> Set.of();
        };
    }

    private static List<String> extractNumerics(String clause) {
        List<String> numerics = new ArrayList<>();
        Matcher matcher = NUMERIC_PATTERN.matcher(clause);
        while (matcher.find()) {
            numerics.add(matcher.group());
        }
        return numerics;
    }
}
