package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 JSON Schema 包装（架构 5.1 / SRS 9.4.2）。
 *
 * <p>仅覆盖工具参数校验所需的子集：{@code type}/{@code properties}/{@code required}/
 * {@code enum}/{@code maxLength}。启动期由 {@code ToolRegistry} 调用 {@link #isStructurallyValid()}
 * 做 Fail-Fast；运行期由编排器调用 {@link #validate(JsonNode)} 做入参校验（SRS 9.4.3 第 31 行）。
 *
 * <p>刻意不引入第三方 Schema 校验库，避免为 MVP 增加重量级依赖。
 */
public final class JsonSchema {

    /** 支持的标量/复合类型关键字。 */
    private static final Set<String> SUPPORTED_TYPES =
            Set.of("string", "integer", "number", "boolean", "object", "array");

    private final JsonNode schema;

    /**
     * 构造器。
     *
     * @param schemaNode Schema 根节点（应为 object 类型）
     */
    public JsonSchema(JsonNode schemaNode) {
        this.schema = schemaNode;
    }

    /**
     * 由 JSON 文本构造。
     *
     * @param json Schema JSON 文本
     * @return Schema 实例；解析失败时内部节点为 null（随后 {@link #isStructurallyValid()} 返回 false）
     */
    public static JsonSchema of(String json) {
        return new JsonSchema(JsonUtils.readTree(json));
    }

    /** 返回下发 LLM 的原始 Schema 节点。 */
    public JsonNode node() {
        return schema;
    }

    /**
     * 结构合法性校验（启动期 Fail-Fast，FR-23 异常流 1a）。
     *
     * @return 合法返回 true：根为 object、{@code type=object}、{@code properties} 若存在须为 object、
     *         {@code required} 若存在须为数组且其元素均为 {@code properties} 中已声明的键
     */
    public boolean isStructurallyValid() {
        if (schema == null || !schema.isObject()) {
            return false;
        }
        JsonNode type = schema.get("type");
        if (type == null || !"object".equals(type.asText())) {
            return false;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            return false;
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) {
                return false;
            }
            for (JsonNode item : required) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    return false;
                }
            }
        }
        // 逐属性校验类型关键字是否受支持
        if (properties != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                JsonNode prop = fields.next().getValue();
                if (!prop.isObject()) {
                    return false;
                }
                JsonNode propType = prop.get("type");
                if (propType != null && !SUPPORTED_TYPES.contains(propType.asText())) {
                    return false;
                }
            }
        }
        return true;
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
