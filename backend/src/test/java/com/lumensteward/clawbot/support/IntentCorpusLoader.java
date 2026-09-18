package com.lumensteward.clawbot.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.intent.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图语料加载器（TODO-05 / 迭代 2 增量 PRD）。
 *
 * <p>从 classpath 的 JSONL 语料（{@code intent-corpus/train.jsonl}、{@code test.jsonl}）加载
 * 监督样本，供意图分类器离线评估/微调使用。<b>纯 IO 解析，不调用任何 LLM</b>。每行格式：
 * <pre>{"text":"...","intent":"PET_PROFILE","slots":{...}}</pre>
 */
public final class IntentCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(IntentCorpusLoader.class);

    private IntentCorpusLoader() {
        // 工具类禁止实例化
    }

    /** 单条语料样本（不可变）。 */
    public static final class IntentSample {
        private final String text;
        private final IntentType expectedIntent;
        private final Map<String, Object> slots;

        public IntentSample(String text, IntentType expectedIntent, Map<String, Object> slots) {
            this.text = text;
            this.expectedIntent = expectedIntent;
            this.slots = slots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        }

        public String text() {
            return text;
        }

        public IntentType expectedIntent() {
            return expectedIntent;
        }

        public Map<String, Object> slots() {
            return slots;
        }
    }

    /**
     * 从 classpath 资源加载语料。
     *
     * @param resourcePath classpath 资源路径（如 {@code intent-corpus/train.jsonl}）
     * @return 样本列表（跳过空行；非法行抛 {@link CorpusFormatException}）
     * @throws IOException 资源不可读或不存在
     * @throws CorpusFormatException 行格式非法或意图未知
     */
    public static List<IntentSample> load(String resourcePath) throws IOException {
        List<IntentSample> samples = new ArrayList<>();
        try (InputStream in = openResource(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                samples.add(parseLine(trimmed, lineNo, resourcePath));
            }
        }
        log.info("意图语料加载完成 resource={} count={}", resourcePath, samples.size());
        return samples;
    }

    private static IntentSample parseLine(String line, long lineNo, String resourcePath) {
        JsonNode node = JsonUtils.readTree(line);
        if (node == null || !node.isObject()) {
            throw new CorpusFormatException(resourcePath, lineNo, "JSON 解析失败或非对象");
        }
        if (!node.hasNonNull("text") || !node.hasNonNull("intent")) {
            throw new CorpusFormatException(resourcePath, lineNo, "缺少 text 或 intent 字段");
        }
        String text = node.get("text").asText();
        String intentName = node.get("intent").asText();
        IntentType intent = parseIntent(resourcePath, lineNo, intentName);
        Map<String, Object> slots = parseSlots(node);
        return new IntentSample(text, intent, slots);
    }

    private static IntentType parseIntent(String resourcePath, long lineNo, String intentName) {
        try {
            return IntentType.valueOf(intentName);
        } catch (IllegalArgumentException e) {
            throw new CorpusFormatException(resourcePath, lineNo, "未知意图: " + intentName);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSlots(JsonNode node) {
        if (!node.hasNonNull("slots") || !node.get("slots").isObject()) {
            return Map.of();
        }
        try {
            return JsonUtils.mapper().convertValue(node.get("slots"), Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static InputStream openResource(String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = IntentCorpusLoader.class.getClassLoader();
        }
        InputStream in = cl.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("语料资源不存在: " + resourcePath);
        }
        return in;
    }

    /** 语料格式异常（行级，携带资源与行号）。 */
    public static final class CorpusFormatException extends RuntimeException {
        public CorpusFormatException(String resource, long lineNo, String reason) {
            super("意图语料格式错误 resource=" + resource + " line=" + lineNo + " reason=" + reason);
        }
    }
}
