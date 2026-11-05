package com.lumensteward.clawbot.domain.task;

import com.lumensteward.clawbot.common.enums.LongTaskStatus;

/**
 * 长任务记录（迭代 4 W15；domain 只读快照，持久化细节在 infrastructure）。
 *
 * @param id          数据库主键
 * @param taskType    任务类型（对应 {@code LongTaskHandler.type()}）
 * @param openid      发起用户（敏感，出参须脱敏，BR-21）
 * @param status      状态
 * @param payloadJson 入参 JSON（handler 自解释）
 * @param resultJson  结果 JSON（成功时非空）
 * @param errorType   失败分类（TIMEOUT / NO_HANDLER / HANDLER_FAILED）
 * @param attempts    已尝试次数（每次 claim +1；重启续跑的次数证据）
 * @param delivered   结果是否已投递（幂等闩：至多一次）
 * @param traceId     提交链路标识（FR-21）
 */
public record LongTaskRecord(Long id, String taskType, String openid, LongTaskStatus status,
                             String payloadJson, String resultJson, String errorType,
                             int attempts, boolean delivered, String traceId) {
}
