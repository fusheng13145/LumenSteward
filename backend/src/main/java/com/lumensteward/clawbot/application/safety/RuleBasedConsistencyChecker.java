package com.lumensteward.clawbot.application.safety;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.model.ActionClaim;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于规则的执行一致性校验（架构 5.2 / SRS 9.4.5）。
 *
 * <p>逐条校验动作声明：
 * <ol>
 *   <li>支持匹配：存在 {@code status=SUCCESS} 且工具语义与声明关键词相交的记录；否则判定
 *       {@link ConsistencyReason#CLAIM_UNSUPPORTED}（执行性幻觉）。</li>
 *   <li>数值可回溯：声明含数值/实体时，其须出现在任一支撑记录的 {@code result_json} 中；否则判定
 *       {@link ConsistencyReason#VALUE_NOT_TRACEABLE}（事实性幻觉）。</li>
 * </ol>
 * {@code strictMode} 开启时，任一失败工具调用存在即在回复前置免责说明（由编排器负责拼接）。
 */
@Component
public class RuleBasedConsistencyChecker implements ConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedConsistencyChecker.class);

    private final ActionClaimExtractor extractor;
    private final SafetyProperties properties;
    private final DynamicConfigService dynamicConfig;

    /**
     * Spring 装配用构造器（G-14）。
     *
     * @param extractor     动作声明抽取器
     * @param properties    安全配置（严格模式静态兜底值）
     * @param dynamicConfig 动态配置源（可为 null）
     */
    @Autowired
    public RuleBasedConsistencyChecker(ActionClaimExtractor extractor, SafetyProperties properties,
                                       DynamicConfigService dynamicConfig) {
        this.extractor = extractor;
        this.properties = properties;
        this.dynamicConfig = dynamicConfig;
    }

    /**
     * 兼容构造（无动态配置源）：保留给脱离 Spring 上下文的单元测试。
     *
     * @param extractor  动作声明抽取器
     * @param properties 安全配置（严格模式）
     */
    public RuleBasedConsistencyChecker(ActionClaimExtractor extractor, SafetyProperties properties) {
        this(extractor, properties, null);
    }

    @Override
    public ConsistencyVerdict check(String reply, List<ToolCallRecord> executedTools) {
        List<ActionClaim> claims = extractor.extract(reply);
        if (claims.isEmpty()) {
            // 边界规则 1：无动作声明（纯闲聊）→ 不做工具校验
            return ConsistencyVerdict.pass();
        }
        List<ToolCallRecord> successes = filterSuccess(executedTools);
        for (ActionClaim claim : claims) {
            List<ToolCallRecord> supporting = findSupporting(claim, successes);
            if (supporting.isEmpty()) {
                log.warn("一致性校验检出：声称执行但无成功记录（BR-04 拦截）");
                return ConsistencyVerdict.unsupported(claim.text());
            }
            if (claim.hasNumerics() && !traceable(claim, supporting)) {
                log.warn("一致性校验检出：数值不可回溯（BR-04 拦截）");
                return ConsistencyVerdict.notTraceable(claim.text());
            }
        }
        return ConsistencyVerdict.pass();
    }

    /** 严格模式开关（供编排器决定是否附免责说明）。运行时可配置（FR-18：{@code safety.strict-mode}）。 */
    public boolean strictMode() {
        if (dynamicConfig == null) {
            return properties.strictMode();
        }
        return dynamicConfig.getBoolean(ConfigKeys.SAFETY_STRICT_MODE, properties.strictMode());
    }

    private List<ToolCallRecord> filterSuccess(List<ToolCallRecord> executed) {
        List<ToolCallRecord> successes = new ArrayList<>();
        if (executed != null) {
            for (ToolCallRecord record : executed) {
                if (record != null && record.status() == ToolStatus.SUCCESS) {
                    successes.add(record);
                }
            }
        }
        return successes;
    }

    private List<ToolCallRecord> findSupporting(ActionClaim claim, List<ToolCallRecord> successes) {
        List<ToolCallRecord> supporting = new ArrayList<>();
        for (ToolCallRecord record : successes) {
            Set<String> semantics = extractor.toolSemantics(record.toolName());
            if (intersects(semantics, claim.keywords())) {
                supporting.add(record);
            }
        }
        return supporting;
    }

    private boolean traceable(ActionClaim claim, List<ToolCallRecord> supporting) {
        StringBuilder haystack = new StringBuilder();
        for (ToolCallRecord record : supporting) {
            if (record.result() != null) {
                haystack.append(record.result().toString());
            }
        }
        String text = haystack.toString();
        for (String numeric : claim.numerics()) {
            if (!text.contains(numeric)) {
                return false;
            }
        }
        return true;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (String item : b) {
            if (a.contains(item)) {
                return true;
            }
        }
        return false;
    }
}
