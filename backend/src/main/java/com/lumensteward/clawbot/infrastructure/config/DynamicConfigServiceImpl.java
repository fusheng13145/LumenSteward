package com.lumensteward.clawbot.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.infrastructure.cache.ConfigCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link DynamicConfigService} 实现（FR-18 / 迭代 2 T10）。
 *
 * <p>取值链路：{@link ConfigCacheService}（Caffeine 本地 → Redis → {@code sys_config}）。
 * 管理后台改值时调用 {@code ConfigCacheService.invalidate(key)}，因此<b>下一次读取即为新值</b>
 * （FR-18 AC①免重启生效）。
 *
 * <p><b>降级契约（BR-04 诚实原则）：</b>配置缺失、值为空、格式非法、缓存不可用，均回退调用方
 * 传入的兜底值并以 WARN 记录，<b>不抛异常、不猜值</b>。这使得"改错一个配置项"不会击穿对话链路。
 */
@Service
public class DynamicConfigServiceImpl implements DynamicConfigService {

    private static final Logger log = LoggerFactory.getLogger(DynamicConfigServiceImpl.class);

    /** 可用性探测键（种子脚本保证存在；其可读即代表配置源可用）。 */
    private static final String PROBE_KEY = ConfigKeys.LLM_MODEL;

    private final ConfigCacheService configCacheService;

    /**
     * 构造器注入（G-14）。
     *
     * @param configCacheService 配置读取缓存（三级）
     */
    public DynamicConfigServiceImpl(ConfigCacheService configCacheService) {
        this.configCacheService = configCacheService;
    }

    @Override
    public String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return configCacheService.get(key);
        } catch (RuntimeException e) {
            log.warn("动态配置读取失败，回退默认值: key={} err={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public String getString(String key, String fallback) {
        String value = get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public int getInt(String key, int fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("动态配置整数解析失败，回退默认值: key={} value={} fallback={}", key, value, fallback);
            return fallback;
        }
    }

    @Override
    public long getLong(String key, long fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("动态配置长整数解析失败，回退默认值: key={} value={} fallback={}", key, value, fallback);
            return fallback;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> {
                log.warn("动态配置布尔解析失败，回退默认值: key={} value={} fallback={}", key, value, fallback);
                yield fallback;
            }
        };
    }

    @Override
    public List<String> getList(String key, List<String> fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        List<String> parsed = parseList(value);
        if (parsed.isEmpty()) {
            return fallback;
        }
        return parsed;
    }

    @Override
    public Set<String> getSet(String key, Set<String> fallback) {
        List<String> list = getList(key, null);
        if (list == null) {
            return fallback;
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(list));
    }

    @Override
    public boolean isAvailable() {
        return get(PROBE_KEY) != null;
    }

    /**
     * 解析列表值：优先按 JSON 数组解析（{@code ["a","b"]}），失败则按逗号分隔（{@code a,b}）。
     *
     * @param raw 原始值
     * @return 解析结果（可能为空列表，元素已去空白）
     */
    private static List<String> parseList(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonNode node = JsonUtils.readTree(trimmed);
                if (node != null && node.isArray()) {
                    List<String> result = new ArrayList<>();
                    for (JsonNode item : node) {
                        String text = item.isTextual() ? item.asText() : item.asText();
                        if (text != null && !text.isBlank()) {
                            result.add(text.trim());
                        }
                    }
                    return result;
                }
            } catch (RuntimeException e) {
                log.warn("动态配置 JSON 数组解析失败，改按逗号分隔: value={} err={}", trimmed, e.getMessage());
            }
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String text = part.trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }
}
