package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.memory.MemoryFact;
import com.lumensteward.clawbot.domain.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 个人状态库召回服务（迭代 4 W6：把跨会话事实带回当轮上下文）。
 *
 * <p>产出的是一个 <b>system 块文本</b>（无内容时 {@code null}），由编排器插在
 * 任务续接提示同一位置。刻意<b>不走 {@code ContextTrimmer}</b>：裁剪器只管历史消息的
 * token 预算，长期记忆必须自带更严的上限（条数 + 字符双闸），否则会挤掉当轮对话。
 *
 * <p>Fail-open：读库异常时返回 {@code null} 并告警——「想不起旧事」不该让本轮回复失败
 * （SRS 9.5 降级矩阵同口径）。
 *
 * <p>提示词中明确要求模型<b>不得据此编造</b>：召回内容是背景知识，不是本轮工具执行结果，
 * 与 BR-04「不编造执行结果」及输出一致性校验互不冲突。
 */
@Service
public class MemoryRecallService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallService.class);

    private static final int DEFAULT_MAX_ITEMS = 8;
    private static final int DEFAULT_MAX_CHARS = 600;

    /** 块头：向模型说明这段内容的性质与使用边界。 */
    private static final String HEADER = """
            以下是该用户此前对话中沉淀的长期信息（跨会话记忆，可能不完整或已过时）。
            仅用于理解语境与称呼；不得据此编造本轮未实际执行的操作或结果。""";

    private final MemoryStore memoryStore;
    private final DynamicConfigService dynamicConfig;

    /**
     * 构造器注入（G-14）。
     *
     * @param memoryStore   状态库存储端口
     * @param dynamicConfig 动态配置源
     */
    public MemoryRecallService(MemoryStore memoryStore, DynamicConfigService dynamicConfig) {
        this.memoryStore = memoryStore;
        this.dynamicConfig = dynamicConfig;
    }

    /**
     * 构建某用户的召回上下文块。
     *
     * @param openid 用户标识（BR-07：仅取该用户自己的条目）
     * @return system 块文本；开关关闭、无条目或读取失败时返回 {@code null}
     */
    public String buildRecallBlock(String openid) {
        if (openid == null || openid.isBlank()) {
            return null;
        }
        if (!enabled()) {
            return null;
        }
        int maxItems = dynamicConfig == null
                ? DEFAULT_MAX_ITEMS : dynamicConfig.getInt(ConfigKeys.MEMORY_RECALL_MAX_ITEMS, DEFAULT_MAX_ITEMS);
        int maxChars = dynamicConfig == null
                ? DEFAULT_MAX_CHARS : dynamicConfig.getInt(ConfigKeys.MEMORY_RECALL_MAX_CHARS, DEFAULT_MAX_CHARS);
        try {
            List<MemoryFact> facts = memoryStore.recallActive(openid, maxItems);
            return render(facts, maxChars);
        } catch (RuntimeException e) {
            log.warn("状态库召回失败，本轮按无长期记忆处理 openid={} err={}",
                    MaskUtils.openid(openid), e.getMessage());
            return null;
        }
    }

    /**
     * 装配召回块（纯函数，便于单测）。
     *
     * @param facts     条目（按最近出现倒序）
     * @param maxChars  字符上限（超出即停止追加）
     * @return 块文本；无可注入条目时 {@code null}
     */
    static String render(List<MemoryFact> facts, int maxChars) {
        if (facts == null || facts.isEmpty() || maxChars <= 0) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        int appended = 0;
        for (MemoryFact fact : facts) {
            String line = "- [" + labelOf(fact.kind()) + "] " + fact.name() + "：" + fact.content();
            if (body.length() + line.length() > maxChars) {
                break;
            }
            body.append(line).append('\n');
            appended++;
        }
        if (appended == 0) {
            return null;
        }
        return HEADER + "\n" + body;
    }

    private static String labelOf(MemoryKind kind) {
        if (kind == null) {
            return "事实";
        }
        return switch (kind) {
            case PERSON -> "人物";
            case PLACE -> "地点";
            case THING -> "物品";
            case PREFERENCE -> "偏好";
            case HABIT -> "惯例";
            case FACT -> "事实";
        };
    }

    private boolean enabled() {
        return dynamicConfig == null || dynamicConfig.getBoolean(ConfigKeys.MEMORY_RECALL_ENABLED, true);
    }
}
