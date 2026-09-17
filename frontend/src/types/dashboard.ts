/**
 * 概览看板域类型（与后端 `interfaces/dto/dashboard/*` 同源）。
 */

/** 看板汇总（数值均可由 SQL 直接复核，非估算） */
export interface DashboardSummaryVO {
  /** 今日消息量 */
  todayMessages: number
  /** 活跃用户数 */
  activeUsers: number
  /** 工具调用总量 */
  toolCalls: number
  /** 成功数 */
  toolSuccess: number
  /** 失败数 */
  toolFailed: number
  /** 降级数 */
  toolDegraded: number
  /** 超时数 */
  toolTimeout: number
  /** 成功率（0~1） */
  successRate: number
  /** 降级次数 */
  degradedCount: number
}
