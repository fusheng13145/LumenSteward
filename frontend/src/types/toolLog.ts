import type { PageQuery } from '@/types/api'

/**
 * 工具调用日志域类型（与后端 `interfaces/dto/toollog/*` 同源）。
 */

/** 工具日志列表视图 */
export interface ToolLogVO {
  /** 主键 */
  id: number
  /** 链路标识 */
  traceId: string
  /** 脱敏后的 openid */
  openid: string
  /** 会话 */
  sessionId: number | null
  /** 工具名 */
  toolName: string
  /** 调用序号 */
  callSeq: number | null
  /** 状态：0-成功 1-失败 2-降级 3-超时 4-未执行 */
  status: number
  /** 异常分类：L1~L4 */
  errorType: string | null
  /** 降级原因 */
  fallbackReason: string | null
  /** 耗时（ms） */
  latencyMs: number | null
  /** Agent Loop 轮次 */
  llmRound: number | null
  /** 创建时间 */
  createdAt: string | null
}

/** 工具日志详情视图 */
export interface ToolLogDetailVO extends ToolLogVO {
  /** 入参（JSON） */
  paramsJson: string | null
  /** 结果（JSON） */
  resultJson: string | null
}

/** 按工具名分组的统计项 */
export interface ToolStatItem {
  toolName: string
  total: number
  success: number
  failed: number
  degraded: number
  timeout: number
  avgLatencyMs: number
}

/** 工具调用统计 */
export interface ToolStatsVO {
  total: number
  success: number
  failed: number
  degraded: number
  timeout: number
  notExecuted: number
  successRate: number
  avgLatencyMs: number
  items: ToolStatItem[]
}

/** 工具日志检索条件 */
export interface ToolLogQuery extends PageQuery {
  /** 链路标识 */
  traceId?: string
  /** 工具名 */
  toolName?: string
  /** 状态 */
  status?: number
  /** 用户 */
  openid?: string
  /** 创建时间下界（ISO 8601） */
  startTime?: string
  /** 创建时间上界（ISO 8601） */
  endTime?: string
}
