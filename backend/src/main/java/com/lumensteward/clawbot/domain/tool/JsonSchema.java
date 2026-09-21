package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 JSON Schema 包装（架构 5.1 / SRS 9.4.2）。
 *
 * <p>仅覆盖工具参数校验所需的子集：{@code type}/{@code properties}/{@code required}/
 * {@code enum}/{@code maxLength}。启动期由 {@code ToolRegistry} 调用 {@link #structuralViolations()}
 * 做 Fail-Fast（违规原因逐条可读）；运行期由编排器调用 {@link #validate(JsonNode)} 做入参校验
 * （SRS 9.4.3 第 31 行）。
 *
 * <p>刻意不引入第三方 Schema 校验库，避免为 MVP 增加重量级依赖。
 */
public final class JsonSchema {

    /** 支持的标量/复合类型关键字。 */
    private static final Set<String> SUPPORTED_TYPES =
            Set.of("string", "integer", "number", "boolean", "object", "array");

    /**
     * 严格的 Schema 文本解析器：开启重复键检测。
     *
     * <p>FR-23 异常流 1a 要求「参数名重复」在启动期即被阻止，而共享 mapper 会静默保留后者，
     * 故 Schema 文本单独用本解析器（仅用于启动期，不进主链路热路径）。
     */
    private static final ObjectMapper STRICT_READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final JsonNode schema;

    /** 文本解析失败原因（非 null 表示 Schema 根本不可解析，{@link #schema} 为 null）。 */
    private final String parseError;

    /**
     * 构造器。
     *
     * @param schemaNode Schema 根节点（应为 object 类型）
     */
    public JsonSchema(JsonNode schemaNode) {
        this(schemaNode, null);
    }

    private JsonSchema(JsonNode schemaNode, String parseFailure) {
        this.schema = schemaNode;
        this.parseError = parseFailure;
    }

    /**
     * 由 JSON 文本构造。
     *
     * @param json Schema JSON 文本
     * @return Schema 实例；解析失败（非法 JSON / 参数名重复）时记录原因，
     *         随后 {@link #structuralViolations()} 给出可读违规项
     */
    public static JsonSchema of(String json) {
        if (json == null || json.isBlank()) {
            return new JsonSchema(null, "Schema 文本为空");
        }
        try {
            return new JsonSchema(STRICT_READER.readTree(json), null);
        } catch (JsonProcessingException e) {
            return new JsonSchema(null, brief(e));
        }
    }

    /** 返回下发 LLM 的原始 Schema 节点。 */
    public JsonNode node() {
        return schema;
    }

    /**
     * 结构合法性校验（启动期 Fail-Fast，FR-23 异常流 1a）。
     *
     * @return 合法（{@link #structuralViolations()} 为空）返回 true
     */
    public boolean isStructurallyValid() {
        return structuralViolations().isEmpty();
    }

    /**
     * 结构违规明细（供启动期输出可读错误，FR-23 异常流 1a / 1b）。
     *
     * <p>逐条给出<b>可定位</b>的原因（哪个字段、缺什么），而不是笼统的「Schema 非法」——
     * 否则新增工具的作者只能靠猜。
     *
     * @return 违规原因列表；合法时为空列表
     */
    public List<String> structuralViolations() {
        List<String> violations = new ArrayList<>();
        if (parseError != null) {
            violations.add("Schema 文本无法解析（参数名重复或非法 JSON）: " + parseError);
            return violations;
        }
        if (schema == null || !schema.isObject()) {
            violations.add("Schema 根节点缺失或不是 object");
            return violations;
        }
        JsonNode type = schema.get("type");
        if (type == null) {
            violations.add("根节点缺少 type（须为 \"object\"）");
        } else if (!"object".equals(type.asText())) {
            violations.add("根节点 type 须为 object，实际为 " + type.asText());
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            violations.add("properties 须为 object");
            properties = null;
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) {
                violations.add("required 须为数组");
            } else {
                for (JsonNode item : required) {
                    if (!item.isTextual() || item.asText().isBlank()) {
                        violations.add("required 元素须为非空字符串，实际为 " + item);
                        continue;
                    }
                    // 承诺与实现一致：required 只能引用 properties 已声明的参数
                    if (properties != null && !properties.has(item.asText())) {
                        violations.add("required 引用了未声明的参数: " + item.asText());
                    }
                }
            }
        }
        // 逐属性校验：每个参数须声明 type，且类型关键字受支持
        if (properties != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode prop = entry.getValue();
                if (!prop.isObject()) {
                    violations.add("参数 " + entry.getKey() + " 的定义须为 object");
                    continue;
                }
                JsonNode propType = prop.get("type");
                if (propType == null) {
                    violations.add("参数 " + entry.getKey() + " 缺少 type");
                } else if (!SUPPORTED_TYPES.contains(propType.asText())) {
                    violations.add("参数 " + entry.getKey() + " 的 type 不受支持: " + propType.asText());
                }
            }
        }
        return violations;
    }

    private static String brief(JsonProcessingException e) {
        String reference = e.getOriginalMessage() == null ? e.getMessage() : e.getOriginalMessage();
        return reference == null ? e.getClass().getSimpleName() : reference;
    }

    /**
     * 校验入参（SRS 9.4.3 第 31 行 schemaValid）。
     *
     * @param args 模型给出的入参（可为 null）
     * @return 校验结果
     */
    public ValidationResult validate(JsonNode args) {
        List<String> violations = new ArrayList<>();

        if (schema == null || !schema.isObject()) {
            violations.add("工具参数 Schema 缺失或非法");
            return ValidationResult.fail(violations);
        }

        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode item : required) {
                String field = item.asText();
                if (args == null || !args.isObject() || !args.has(field) || args.get(field).isNull()) {
                    violations.add("缺少必填字段: " + field);
                }
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject() && args != null && args.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode value = args.get(name);
                if (value == null || value.isNull()) {
                    continue;
                }
                checkType(name, entry.getValue(), value, violations);
                checkEnum(name, entry.getValue(), value, violations);
                checkMaxLength(name, entry.getValue(), value, violations);
            }
        }

        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(violations);
    }

    private void checkType(String name, JsonNode propSchema, JsonNode value, List<String> violations) {
        JsonNode typeNode = propSchema.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            return;
        }
        String type = typeNode.asText();
        boolean matched = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true;
        };
        if (!matched) {
            violations.add("字段 " + name + " 类型应为 " + type);
        }
    }

    private void checkEnum(String name, JsonNode propSchema, JsonNode value, List<String> violations) {
        JsonNode enumNode = propSchema.get("enum");
        if (enumNode == null || !enumNode.isArray()) {
            return;
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        for (JsonNode allowed : enumNode) {
            if (allowed.asText().equals(text)) {
                return;
            }
        }
        violations.add("字段 " + name + " 取值非法: " + text);
    }

    private void checkMaxLength(String name, JsonNode propSchema, JsonNode value, List<String> violations) {
        JsonNode maxLength = propSchema.get("maxLength");
        if (maxLength == null || !maxLength.isIntegralNumber() || !value.isTextual()) {
            return;
        }
        if (value.asText().length() > maxLength.asInt()) {
            violations.add("字段 " + name + " 超长（上限 " + maxLength.asInt() + "）");
        }
    }
}
