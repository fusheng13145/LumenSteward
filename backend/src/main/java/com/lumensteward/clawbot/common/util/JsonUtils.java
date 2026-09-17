package com.lumensteward.clawbot.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 序列化工具（8.4）。
 *
 * <p>提供共享的 {@link ObjectMapper}：启用 JavaTime（ISO 8601，禁用时间戳）、忽略未知字段、
 * 不因单条记录失败而整体中断。用于日志埋点、Redis 值序列化与测试构造。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = buildMapper();

    private JsonUtils() {
        // 工具类禁止实例化
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /** 返回共享的 ObjectMapper（只读用途，请勿修改其配置）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 对象转 JSON 字符串。
     *
     * @param value 任意对象
     * @return JSON 字符串；序列化失败返回 null
     */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * JSON 字符串转对象。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T>  目标泛型
     * @return 反序列化结果；入参为空返回 null
     */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * JSON 字符串转泛型对象（如 {@code List<Foo>}）。
     *
     * @param json JSON 字符串
     * @param type 目标泛型类型引用
     * @param <T>  目标泛型
     * @return 反序列化结果；入参为空返回 null
     */
    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 解析为 JSON 树，便于工具参数校验与结果装配（FR-23）。
     *
     * @param json JSON 字符串
     * @return JSON 树；解析失败返回 null
     */
    public static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
