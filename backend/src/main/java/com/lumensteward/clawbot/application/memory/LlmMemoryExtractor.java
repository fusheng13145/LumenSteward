package com.lumensteward.clawbot.application.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的记忆抽取器（迭代 4 W6 生长管道的「抽取」环节）。
 *
 * <p>与 {@code LlmIntentClassifier} 同范式：受约束 JSON 输出 + 解析失败即空列表，
 * <b>绝不向主链路抛出</b>（生长失败只是「这条没记住」，不是「这次回复失败」）。
 *
 * <p>本调用是<b>链路成功之后的额外一次模型调用</b>，因此其 token 消耗同样计入
 * FR-20 ③ 日预算（{@link CostBudgetService#recordLlmCall(int)}），不隐藏成本。
 *
 * <p>超时刻意短于主链路单轮预算（SC-03 的 8s）：本调用在后台线程，慢不如弃。
 */
@Component
public class LlmMemoryExtractor implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);

    /** 抽取器标识与版本，落 {@code biz_memory_item.extractor}（溯源：谁写下的这条记忆）。 */
    public static final String EXTRACTOR_ID = "llm-extract-v1";

    /**
     * 抽取请求的载荷前缀标记。
     *
     * <p>刻意使用主链路不会自然出现的串，使 Mock 脚本可用一条<b>置于首位</b>的规则命中
     * （载荷内含用户原文，若用常见字词会与 {@code pet-create-toolcall} 等规则冲突）。
     */
    public static final String PAYLOAD_MARKER = "【记忆抽取】";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 单次抽取条数硬上限（即使配置给得更大也夹紧，防一次对话灌进一屏噪声）。 */
    private static final int HARD_MAX_ITEMS = 10;

    private static final String SYSTEM_PROMPT = """
            你是"衔光管家"的记忆抽取器。从下面这轮对话中抽取【用户亲口说出的、可跨会话复用的长期事实】，只输出 JSON。
            格式：{"items":[{"kind":"PERSON|PLACE|THING|PREFERENCE|HABIT|FACT","name":"实体名或偏好键","content":"一句话事实(≤60字)","confidence":0.0-1.0}]}
            规则：
            1) 只抽用户明确陈述的内容；提问、推测、一次性请求、当下才成立的信息一律不抽；
            2) 承诺、待办、提醒不抽（属另一子系统）；
            3) 没有合格事实就输出 {"items":[]}，不要凑数；
            4) 不复述手机号、证件号、密钥等敏感原文，用关系或类别代替；
            5) 只输出 JSON，不要任何解释文字。""";

    private final LlmClient llmClient;
    /** 成本预算服务（可为 null，此时不计入日预算）。 */
    private final CostBudgetService costBudgetService;

    /**
     * 构造器注入（G-14）。
     *
     * @param llmClient           LLM 客户端
     * @param costBudgetService   成本保护预算服务（可为 null）
     */
    public LlmMemoryExtractor(LlmClient llmClient, CostBudgetService costBudgetService) {
        this.llmClient = llmClient;
        this.costBudgetService = costBudgetService;
    }

    @Override
    public List<MemoryWrite> extract(MemoryGrowthNotice notice, int maxItems) {
        if (notice == null || !notice.worthExtracting() || maxItems <= 0) {
            return List.of();
        }
        List<ChatMessage> messages = List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(PAYLOAD_MARKER + "\n用户：" + notice.userMessage()
                        + "\n管家：" + notice.assistantReply()));
        ChatResult result;
        try {
            result = llmClient.chat(ChatRequest.of(null, messages, List.of(), TIMEOUT));
        } catch (LlmException e) {
            log.warn("记忆抽取调用失败，本轮不生长: errType={}", e.errorType());
            return List.of();
        } catch (RuntimeException e) {
            log.warn("记忆抽取异常，本轮不生长: err={}", e.getMessage());
            return List.of();
        }
        recordUsage(result);
        return parseCandidates(result.content(), notice, Math.min(maxItems, HARD_MAX_ITEMS));
    }

    private List<MemoryWrite> parseCandidates(String content, MemoryGrowthNotice notice, int cap) {
        JsonNode root = JsonUtils.readTree(stripToJson(content));
        if (root == null) {
            return List.of();
        }
        JsonNode items = root.isArray() ? root : root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<MemoryWrite> writes = new ArrayList<>();
        for (JsonNode item : items) {
            if (writes.size() >= cap) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String name = textOf(item, "name");
            String fact = textOf(item, "content");
            if (name == null || fact == null) {
                continue;
            }
            MemoryKind kind = MemoryKind.parse(textOf(item, "kind"));
            writes.add(new MemoryWrite(notice.openid(),
                    kind == null ? MemoryKind.FACT : kind,
                    name, fact, MemoryOrigin.AUTO_EXTRACT, EXTRACTOR_ID,
                    confidenceOf(item.get("confidence")),
                    notice.sessionId(), notice.traceId()));
        }
        return writes;
    }

    /**
     * 从模型输出中截出 JSON 主体（容忍 ```json 围栏与前后解释文字）。
     *
     * @param content 原始输出
     * @return 可解析的 JSON 串；无括号时返回 null
     */
    private static String stripToJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int objStart = content.indexOf('{');
        int arrStart = content.indexOf('[');
        int start;
        char close;
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            start = arrStart;
            close = ']';
        } else if (objStart >= 0) {
            start = objStart;
            close = '}';
        } else {
            return null;
        }
        int end = content.lastIndexOf(close);
        return end > start ? content.substring(start, end + 1) : null;
    }

    private static String textOf(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** 置信度只接受数值且在 [0,1] 内；文本或越界值一律置空（宁缺不猜）。 */
    private static BigDecimal confidenceOf(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        if (value < 0.0 || value > 1.0) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    private void recordUsage(ChatResult response) {
        if (costBudgetService == null || response == null || response.usage() == null) {
            return;
        }
        costBudgetService.recordLlmCall(response.usage().totalTokens());
    }
}
