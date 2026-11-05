package com.lumensteward.clawbot.interfaces.dto.task;

/**
 * 长任务视图（迭代 4 W15）：openid 一律脱敏形态（BR-21，由 MaskingAssembler 装配）。
 *
 * @param id         任务 id
 * @param taskType   任务类型
 * @param openid     发起用户（脱敏）
 * @param status     状态（PENDING/RUNNING/SUCCEEDED/FAILED）
 * @param resultJson 结果 JSON（成功时）
 * @param errorType  失败分类
 * @param attempts   已尝试次数（重启续跑的次数证据）
 * @param delivered  结果是否已投递（幂等闩）
 * @param traceId    提交链路标识
 */
public record LongTaskVO(Long id, String taskType, String openid, String status,
                         String resultJson, String errorType, int attempts, boolean delivered,
                         String traceId) {
}
