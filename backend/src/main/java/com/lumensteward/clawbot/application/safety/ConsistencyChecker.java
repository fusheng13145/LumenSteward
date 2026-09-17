package com.lumensteward.clawbot.application.safety;

import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;

import java.util.List;

/**
 * 执行一致性校验（架构 5.2 / SRS 9.4.5，对应 2.3.5 L4 判据）。
 *
 * <p>核心：把"回复中的动作声明"与"本次链路实际成功的工具调用"比对；<b>任一声明无成功支撑即判定
 * 整条回复为幻觉</b>（保守策略，9.4.5 边界规则 2）。
 */
public interface ConsistencyChecker {

    /**
     * 校验。
     *
     * @param reply         模型终态回复
     * @param executedTools 本次链路已执行的工具记录
     * @return 校验结论
     */
    ConsistencyVerdict check(String reply, List<ToolCallRecord> executedTools);
}
