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

/**
 * 工具调用回放请求（A-2 / 迭代 2 T12）。
 *
 * `dryRun` 缺省为 true：非只读工具不实际执行，避免调试动作改写业务数据。
 */
export interface ReplayRequest {
  /** 链路标识（整条回放必填；单条回放忽略） */
  traceId?: string
  /** 待复现校验的回复文本（给出即重跑执行一致性校验） */
  reply?: string
  /** 是否干跑（后端缺省按 true 处理） */
  dryRun?: boolean
}

/** 回放单项结果 */
export interface ReplayItemVO {
  /** 历史日志主键 */
  logId: number
  /** 工具名 */
  toolName: string
  /** 历史入参（JSON） */
  paramsJson: string | null
  /** 历史状态 */
  originalStatus: string
  /** 本次回放状态 */
  replayStatus: string
  /** 异常分类 */
  errorType: string | null
  /** 说明（跳过时给出跳过原因） */
  message: string | null
  /** 本次结果（JSON） */
  dataJson: string | null
  /** 本次耗时（ms） */
  latencyMs: number
  /** 是否被跳过 */
  skipped: boolean
  /** 跳过原因 */
  skipReason: string | null
}

/** 一致性复现结果 */
export interface ReplayConsistencyVO {
  /** 是否执行了复现（需提供回复文本） */
  replayed: boolean
  /** 是否复现出拦截 */
  intercepted: boolean
  /** 命中问题的声明原文 */
  claimText: string | null
  /** 判定原因 */
  reason: string | null
}

/** 回放结果视图 */
export interface ReplayResultVO {
  /** 链路标识 */
  traceId: string
  /** 脱敏后的 openid */
  openid: string
  /** 是否干跑 */
  dryRun: boolean
  /** 逐条回放项 */
  items: ReplayItemVO[]
  /** 一致性复现结果 */
  consistency: ReplayConsistencyVO
}
