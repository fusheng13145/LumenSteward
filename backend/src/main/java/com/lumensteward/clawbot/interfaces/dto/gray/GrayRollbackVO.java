package com.lumensteward.clawbot.interfaces.dto.gray;

import java.util.List;

/**
 * 灰度回滚结果（POST /api/gray/rollback 出参）。
 *
 * @param rolledBack  被置 0 的灰度功能数（0 表示本就无放量，未产生写入）
 * @param fromPercent 变更明细（{@code 代号=原比例}）
 */
public record GrayRollbackVO(int rolledBack, List<String> fromPercent) {
}
