package com.lumensteward.clawbot.application.task;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 长任务处理器 SPI（迭代 4 W15）：每个任务类型一个实现 Bean，注册即生效
 * （类型重复阻止启动，与 {@code MessageDispatcher} 同一口径）。
 *
 * <p>实现要求：{@link #execute} 必须<b>可重入</b>——重启续跑会对未完成任务再次执行
 * （attempts +1），handler 不得依赖「只跑一次」的进程内状态；产出必须是<b>事实性结果</b>
 * （给 describe 叙述用），失败一律抛异常由执行器转 {@code HANDLER_FAILED}，不自行吞掉。
 */
public interface LongTaskHandler {

    /** 任务类型（全局唯一，对应 {@code biz_long_task.task_type}）。 */
    String type();

    /**
     * 执行任务。
     *
     * @param payload 入参 JSON（biz_long_task.payload_json）
     * @return 结果 JSON（落 result_json，供投递叙述与查询）
     * @throws Exception 任何失败——执行器统一捕获分类，不要求实现方自行兜底
     */
    JsonNode execute(JsonNode payload) throws Exception;

    /**
     * 结果的人话摘要（任务完成后经客服消息投递给用户用）。
     *
     * @param result 执行结果
     * @return 单行摘要；默认原样输出结果 JSON 的紧凑形态
     */
    default String describe(JsonNode result) {
        return result == null ? "" : result.toString();
    }
}
