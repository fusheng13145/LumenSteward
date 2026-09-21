package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具参数 Schema 单测（SRS 9.4.3 第 31 行入参校验 + FR-23 异常流 1a + BR-32 契约兼容）。
 *
 * <p>运行期只关心两件事：<b>该拒的拒</b>（缺必填、类型不符、枚举越界、超长），
 * <b>不该拒的不拒</b>（新增可选参数后，旧调用方的入参依然合法——BR-32 的向后兼容正身）。
 */
class JsonSchemaTest {

    /** 与 {@code QueryWeatherTool} 同形的样例 Schema。 */
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "city":{"type":"string","description":"城市","maxLength":32},
              "date":{"type":"string","description":"日期，可空"},
              "level":{"type":"integer","description":"等级"},
              "strict":{"type":"boolean","description":"是否严格"},
              "mode":{"type":"string","enum":["day","night"]}
            },"required":["city"]}
            """;

    private final JsonSchema schema = JsonSchema.of(SCHEMA);

    private static JsonNode args(String json) {
        return JsonUtils.readTree(json);
    }

    @Test
    @DisplayName("合法入参通过校验")
    void validArgsPass() {
        ValidationResult result = schema.validate(args("{\"city\":\"杭州\",\"level\":3,\"strict\":true}"));

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("缺必填字段 → 违规并指明字段名")
    void missingRequiredField() {
        ValidationResult result = schema.validate(args("{\"date\":\"2026-09-21\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("city"));
    }

    @Test
    @DisplayName("类型不符 / 枚举越界 / 超长 → 逐条违规")
    void typeEnumAndLengthViolations() {
        ValidationResult result = schema.validate(
                args("{\"city\":\"" + "杭".repeat(40) + "\",\"level\":\"三\",\"mode\":\"noon\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(3);
        assertThat(String.join("；", result.violations()))
                .contains("level")
                .contains("mode")
                .contains("city");
    }

    @Test
    @DisplayName("入参为空（null / 非 object）→ 违规而非异常")
    void nullArgsFailValidation() {
        assertThat(schema.validate(null).valid()).isFalse();
        assertThat(schema.validate(args("[1,2]")).valid()).isFalse();
    }

    @Test
    @DisplayName("BR-32：新增可选参数不破坏既有调用（旧入参依然合法）")
    void optionalParameterIsBackwardCompatible() {
        // 契约演进：只加可选参数（不动 required）→ 老调用方的入参必须仍然通过
        JsonSchema upgraded = JsonSchema.of(SCHEMA.replace(
                "\"strict\":{\"type\":\"boolean\",\"description\":\"是否严格\"},",
                "\"strict\":{\"type\":\"boolean\",\"description\":\"是否严格\"},"
                        + "\"wind\":{\"type\":\"string\",\"description\":\"新增可选参数\"},"));

        assertThat(upgraded.isStructurallyValid()).isTrue();
        assertThat(upgraded.validate(args("{\"city\":\"杭州\"}")).valid()).isTrue();
        assertThat(upgraded.validate(args("{\"city\":\"杭州\"}")).violations()).isEmpty();
    }

    @Test
    @DisplayName("异常流 1a：required 引用未声明参数 → 结构非法且原因可读")
    void requiredMustBeDeclared() {
        JsonSchema broken = JsonSchema.of(
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"date\"]}");

        assertThat(broken.isStructurallyValid()).isFalse();
        assertThat(broken.structuralViolations())
                .containsExactly("required 引用了未声明的参数: date");
    }

    @Test
    @DisplayName("结构合法时违规列表为空（与 isStructurallyValid 同源）")
    void validSchemaHasNoViolations() {
        assertThat(schema.isStructurallyValid()).isTrue();
        assertThat(schema.structuralViolations()).isEmpty();
    }

    @Test
    @DisplayName("非法 JSON 文本 → 解析失败原因被保留并随违规返回")
    void unparseableTextIsReported() {
        JsonSchema broken = JsonSchema.of("{not-json");

        assertThat(broken.isStructurallyValid()).isFalse();
        List<String> violations = broken.structuralViolations();
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("Schema 文本无法解析");
    }
}
