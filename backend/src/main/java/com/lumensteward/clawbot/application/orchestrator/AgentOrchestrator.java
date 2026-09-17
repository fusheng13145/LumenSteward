package com.lumensteward.clawbot.application.orchestrator;

import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;

/**
 * Agent 编排器（架构 5.2 / SRS 9.4.3）。
 *
 * <p>实现 SC-01（最大 5 轮）/ SC-02（最大 3 并行工具）/ SC-03（总 25s、单工具 8s）/
 * SC-04（强制收敛）/ SC-05（关键工具中断）。
 */
public interface AgentOrchestrator {

    /**
     * 执行一次完整编排。
     *
     * @param request 编排请求
     * @return 编排结果
     */
    OrchestrationResult run(OrchestrationRequest request);
}
