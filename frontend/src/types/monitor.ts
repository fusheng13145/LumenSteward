/**
 * 监控看板域类型（FR-17 / T4 + A-3 / T5，与后端 `interfaces/dto/monitor/*` 同源）。
 *
 * 数值均为后端聚合查询直接产出（COUNT/SUM/AVG），可用 SQL 复核，非估算（AC-E5）。
 */

/** 监控概览 KPI（GET /api/monitor/overview） */
export interface MonitorOverviewVO {
  /** 今日消息量 */
  todayMessages: number
  /** 活跃用户数 */
  activeUsers: number
  /** 工具调用总量 */
  toolCalls: number
  /** 工具成功率（0~1） */
  successRate: number
  /** 工具平均耗时（ms） */
  avgLatencyMs: number
  /** 降级次数（status=2） */
  degradedCount: number
}

/** 工具调用趋势点（GET /api/monitor/trend） */
export interface TrendPointVO {
  /** 时间桶 */
  bucket: string
  /** 调用量 */
  total: number
  /** 成功数 */
  success: number
  /** 成功率（0~1） */
  successRate: number
}

/** 按工具名分组的成功率（GET /api/monitor/success-rate） */
export interface SuccessRateVO {
  /** 工具名 */
  toolName: string
  /** 调用量 */
  total: number
  /** 成功数 */
  success: number
  /** 成功率（0~1） */
  successRate: number
}

/** 意图分布切片（GET /api/monitor/intent-distribution） */
export interface IntentSliceVO {
  /** 意图域标签 */
  intent: string
  /** 调用量 */
  count: number
}

/** 延迟分布桶（GET /api/monitor/latency-distribution） */
export interface LatencyBucketVO {
  /** 区间标签（ms） */
  range: string
  /** 计数 */
  count: number
}

/** 四层异常分布项（对齐 SRS 2.3.5） */
export interface AnomalyLayerVO {
  /** 层次标识（L1/L2/L3/L4） */
  layer: string
  /** 层次中文名 */
  label: string
  /** 计数 */
  count: number
}

/** 降级与拦截看板指标（GET /api/monitor/degrade） */
export interface DegradeMetricsVO {
  /** 工具调用总量 */
  totalCalls: number
  /** 降级触发数（status=2 与 3 之和） */
  degradedCalls: number
  /** 降级数（status=2） */
  degradedCount: number
  /** 超时数（status=3） */
  timeoutCount: number
  /** 降级触发率（0~1） */
  degradeRate: number
  /** 执行性幻觉拦截次数 */
  hallucinationInterceptions: number
  /** 对外泄漏次数（核心 KPI，应为 0） */
  externalLeakCount: number
  /** 泄漏 KPI 是否达标 */
  leakKpiPass: boolean
  /** 四层异常分布 */
  anomalyDistribution: AnomalyLayerVO[]
}

/** 监控查询时间范围（ISO 8601 字符串） */
export interface MonitorRangeQuery {
  /** 下界（可空） */
  start?: string
  /** 上界（可空） */
  end?: string
  /** 趋势粒度：DAY 按天（缺省），HOUR 按小时 */
  granularity?: 'DAY' | 'HOUR'
}

/** 链路时序 span 类型（对齐后端 OrchestrationSpan.SpanKind） */
export type SpanKind = 'LLM_ROUND' | 'TOOL'

/** 链路时序 span 状态（对齐后端 OrchestrationSpan.SpanStatus） */
export type SpanStatus = 'OK' | 'FAIL' | 'DEGRADED' | 'TIMEOUT'

/** 链路时序 span（GET /api/monitor/trace/{traceId}） */
export interface TraceSpanVO {
  /** span 类型：LLM 轮次 / 工具调用 */
  kind: SpanKind
  /** 链路内顺序号（从 1 递增） */
  seq: number
  /** 所属 Agent Loop 轮次（0 基） */
  round: number
  /** span 名称（工具名或 "LLM#round"） */
  name: string
  /** 相对链路起点的开始偏移（ms） */
  startOffsetMs: number
  /** 持续耗时（ms） */
  durationMs: number
  /** 执行状态 */
  status: SpanStatus
}

/** 单次链路时序瀑布（GET /api/monitor/trace/{traceId}，A-5 / T6） */
export interface TraceWaterfallVO {
  /** 链路标识 */
  traceId: string
  /** 链路总耗时（ms） */
  totalMs: number
  /** 链路总时间预算（ms，SC-03） */
  totalBudgetMs: number
  /** Agent Loop 轮次 */
  rounds: number
  /** 是否超出总预算 */
  exceededBudget: boolean
  /** 时序 span 列表 */
  spans: TraceSpanVO[]
}
