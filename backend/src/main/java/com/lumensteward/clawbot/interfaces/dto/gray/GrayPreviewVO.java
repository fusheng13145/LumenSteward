package com.lumensteward.clawbot.interfaces.dto.gray;

/**
 * 单用户灰度命中预览（FR-22 / W2）。
 *
 * <p>用于运维核对「这个用户为什么在/不在灰度里」，也是分流比例的自证入口。
 * openid 只以脱敏形态回显（BR-21）。
 *
 * @param featureCode 灰度代号
 * @param label       中文名称
 * @param openid      脱敏后的用户标识
 * @param percent     当前比例
 * @param bucket      该用户的稳定分桶（未参与计算为 -1）
 * @param hit         是否命中
 * @param reason      判定原因（GRAY_OFF / FULL_ROLLOUT / WHITELIST / PERCENT / NOT_IN_GRAY / MISSING_OPENID）
 */
public record GrayPreviewVO(String featureCode, String label, String openid,
                            int percent, int bucket, boolean hit, String reason) {
}
