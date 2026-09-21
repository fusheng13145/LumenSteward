package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.infrastructure.cache.ConfigCacheService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysConfigMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 系统配置写服务（FR-18 / 迭代 2 T10）：校验 → 落库 → 缓存失效 → 审计留痕。
 *
 * <p>与 MVP 骨架（{@code ConfigQueryService} 仅落库、不热更新）的差别，正是 FR-18 的四条验收：
 * <ol>
 *   <li><b>免重启生效（AC①）：</b>落库后调用 {@code ConfigCacheService.invalidate(key)}，
 *       使本地与 Redis 缓存立即失效，下一次读取即新值；运行时读取方统一走
 *       {@code DynamicConfigService}。</li>
 *   <li><b>密钥不回明（AC②）：</b>审计留痕与日志对 {@code SECRET} 类型一律
 *       {@link MaskUtils#secret(String)}；出参遮罩由 {@code MaskingAssembler} 统一承担。</li>
 *   <li><b>变更前后值留痕（AC③）：</b>非密钥配置记录可读的前/后值（截断 500 字），
 *       密钥仅尾号——留痕<b>可比对</b>而不泄露明文。</li>
 *   <li><b>非法值被拒（AC④）：</b>按 {@code value_type} 校验；工具开关额外校验必须是已注册工具名，
 *       杜绝"禁用一个不存在的工具"这类静默无效操作。</li>
 * </ol>
 *
 * <p>依赖铁律（NFR-MA-03）：本服务位于 application 层，仅依赖 domain 抽象
 * （{@link ToolRegistry}）与 infrastructure 的 Mapper / 缓存实现。
 */
@Service
public class ConfigAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminService.class);

    /** 配置值最大长度（超出即拒绝，防止误贴大段文本入库）。 */
    private static final int MAX_VALUE_LENGTH = 2000;

    /** 审计留痕的单项最大长度。 */
    private static final int AUDIT_VALUE_LIMIT = 500;

    /** 值类型：整数。 */
    private static final String TYPE_INT = "INT";

    /** 值类型：布尔。 */
    private static final String TYPE_BOOL = "BOOL";

    /** 值类型：JSON。 */
    private static final String TYPE_JSON = "JSON";

    /** 值类型：密钥。 */
    private static final String TYPE_SECRET = "SECRET";

    /** 灰度配置键前缀（FR-22）。 */
    private static final String GRAY_KEY_PREFIX = "gray.";

    /** 灰度比例配置键后缀（FR-22 异常流 2a 值域校验）。 */
    private static final String PERCENT_KEY_SUFFIX = ".percent";

    private final SysConfigMapper sysConfigMapper;
    private final AuditLogService auditLogService;
    private final ConfigCacheService configCacheService;
    private final ToolRegistry toolRegistry;

    /**
     * 构造器注入（G-14）。
     *
     * @param sysConfigMapper     配置 Mapper
     * @param auditLogService     审计写服务
     * @param configCacheService  配置缓存（变更后失效，热生效关键）
     * @param toolRegistry        工具注册中心（校验工具开关取值）
     */
    public ConfigAdminService(SysConfigMapper sysConfigMapper,
                              AuditLogService auditLogService,
                              ConfigCacheService configCacheService,
                              ToolRegistry toolRegistry) {
        this.sysConfigMapper = sysConfigMapper;
        this.auditLogService = auditLogService;
        this.configCacheService = configCacheService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 批量更新配置（须 reason，BR-25）。
     *
     * @param items   更新项（键必须已存在，不存在即拒绝——防止凭空造配置项）
     * @param reason  变更原因
     * @param adminId 操作人
     * @param ip      来源 IP
     * @return 实际更新条数
     */
    public int update(List<ConfigItem> items, String reason, Long adminId, String ip) {
        if (items == null || items.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "更新项不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "变更原因必填（BR-25）");
        }
        int updated = 0;
        for (ConfigItem item : items) {
            if (item == null || item.configKey() == null || item.configKey().isBlank()) {
                throw BizException.of(ErrorCode.PARAM_MISSING, "配置键不能为空");
            }
            SysConfigEntity entity = require(item.configKey());
            String newValue = item.configValue() == null ? "" : item.configValue();
            validate(entity, newValue);

            String before = auditValue(entity.getValueType(), entity.getConfigValue());
            entity.setConfigValue(newValue);
            entity.setUpdatedBy(adminId);
            sysConfigMapper.updateById(entity);
            String after = auditValue(entity.getValueType(), newValue);
            // 热生效：本地 + Redis 同时失效（FR-18 AC①）
            configCacheService.invalidate(entity.getConfigKey());
            audit(adminId, "CONFIG_UPDATE", entity.getConfigKey(), before, after, reason, ip);
            updated++;
        }
        log.info("配置已更新并即时生效，共 {} 项（无需重启）", updated);
        return updated;
    }

    /**
     * 恢复默认值（写回 {@code default_value} 并失效缓存）。
     *
     * @param configKey 键名
     * @param adminId   操作人
     * @param ip        来源 IP
     */
    public void reset(String configKey, Long adminId, String ip) {
        SysConfigEntity entity = require(configKey);
        String before = auditValue(entity.getValueType(), entity.getConfigValue());
        String restored = entity.getDefaultValue() == null ? "" : entity.getDefaultValue();
        entity.setConfigValue(restored);
        entity.setUpdatedBy(adminId);
        sysConfigMapper.updateById(entity);
        configCacheService.invalidate(configKey);
        audit(adminId, "CONFIG_RESET", configKey, before,
                auditValue(entity.getValueType(), restored), "恢复默认值", ip);
    }

    /**
     * 按键名取配置项（不存在即拒绝）。
     *
     * @param configKey 键名
     * @return 配置实体
     */
    public SysConfigEntity require(String configKey) {
        SysConfigEntity entity = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfigEntity>()
                .eq(SysConfigEntity::getConfigKey, configKey)
                .last("LIMIT 1"));
        if (entity == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "配置项不存在: " + configKey);
        }
        return entity;
    }

    /**
     * 值校验（AC④）：按 {@code value_type} 拒绝非法值；工具开关额外校验工具名已注册。
     *
     * @param entity 配置实体（提供类型信息）
     * @param value  待写入值
     */
    public void validate(SysConfigEntity entity, String value) {
        if (value != null && value.length() > MAX_VALUE_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "配置值超长（上限 " + MAX_VALUE_LENGTH + "）: " + entity.getConfigKey());
        }
        String type = entity.getValueType() == null ? "STRING" : entity.getValueType().toUpperCase(Locale.ROOT);
        switch (type) {
            case TYPE_INT -> requireInt(entity, value);
            case TYPE_BOOL -> requireBool(entity, value);
            case TYPE_JSON -> requireJson(entity, value);
            default -> {
                // STRING / SECRET 允许空值与任意字符（如 base-url 可空、token 由环境变量注入）
            }
        }
        if (ConfigKeys.ORCHESTRATION_DISABLED_TOOLS.equals(entity.getConfigKey())) {
            validateDisabledTools(value);
        }
        if (entity.getConfigKey().startsWith(GRAY_KEY_PREFIX) && entity.getConfigKey().endsWith(PERCENT_KEY_SUFFIX)) {
            requireGrayPercent(entity, value);
        }
    }

    /**
     * 灰度比例值域校验（FR-22 异常流 2a：比例 &gt; 100% 属配置错误，直接拒绝）。
     *
     * @param entity 配置实体
     * @param value  待写入值（已由 {@link #requireInt} 保证为整数）
     */
    private static void requireGrayPercent(SysConfigEntity entity, String value) {
        int percent = Integer.parseInt(value.trim());
        if (percent < 0 || percent > 100) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "灰度比例必须在 0~100 之间，实际为: " + entity.getConfigKey() + "=" + percent);
        }
    }

    private static void requireInt(SysConfigEntity entity, String value) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "配置值必须为整数: " + entity.getConfigKey());
        }
        try {
            Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "配置值必须为整数，实际为: " + entity.getConfigKey() + "=" + value);
        }
    }

    private static void requireBool(SysConfigEntity entity, String value) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "配置值必须为布尔: " + entity.getConfigKey());
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("true", "false", "1", "0").contains(normalized)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "配置值必须为 true/false: " + entity.getConfigKey() + "=" + value);
        }
    }

    private static void requireJson(SysConfigEntity entity, String value) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "配置值必须为合法 JSON: " + entity.getConfigKey());
        }
        try {
            if (JsonUtils.readTree(value) == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "配置值必须为合法 JSON: " + entity.getConfigKey());
            }
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "配置值必须为合法 JSON: " + entity.getConfigKey() + "=" + value);
        }
    }

    /**
     * 工具开关校验：必须是 JSON 数组，且元素全部为已注册工具名（防止静默无效）。
     *
     * @param value 待写入值
     */
    private void validateDisabledTools(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "工具开关必须为 JSON 数组，如 []");
        }
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = JsonUtils.readTree(value);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "工具开关必须为 JSON 数组，如 [\"plan_route\"]");
        }
        if (node == null || !node.isArray()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "工具开关必须为 JSON 数组，如 [\"plan_route\"]");
        }
        if (toolRegistry == null) {
            return;
        }
        Set<String> registered = toolRegistry.names();
        for (com.fasterxml.jackson.databind.JsonNode item : node) {
            String name = item.isTextual() ? item.asText() : null;
            if (name != null && !name.isBlank() && !registered.contains(name)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "工具开关含未注册工具名: " + name + "，已注册: " + registered);
            }
        }
    }

    /**
     * 审计值：SECRET 仅尾号，其余截断后原样留痕（AC②/③）。
     *
     * @param valueType 值类型
     * @param value     原始值
     * @return 可安全写入 log_audit 的值
     */
    private static String auditValue(String valueType, String value) {
        if (TYPE_SECRET.equalsIgnoreCase(valueType)) {
            return MaskUtils.secret(value);
        }
        if (value == null) {
            return null;
        }
        return value.length() > AUDIT_VALUE_LIMIT ? value.substring(0, AUDIT_VALUE_LIMIT) + "…" : value;
    }

    private void audit(Long adminId, String action, String target, String before, String after,
                       String reason, String ip) {
        try {
            auditLogService.record(adminId, "CONFIG", action, target, before, after, reason, ip, 1);
        } catch (RuntimeException e) {
            log.warn("配置审计写失败: err={}", e.getMessage());
        }
    }

    /**
     * 配置更新项。
     *
     * @param configKey   键名
     * @param configValue 新值
     */
    public record ConfigItem(String configKey, String configValue) {
    }
}
