/**
 * 成本与配额治理看板域类型（迭代 4 W5 / B-4，与后端 `interfaces/dto/cost/CostVO` 同源）。
 */

/** 时间区间查询条件（start/end 为 ISO 局部时间串，可空即不限） */
export interface CostRangeQuery {
  /** 下界 */
  start?: string
  /** 上界 */
  end?: string
}

/** 概览 KPI（GET /api/cost/overview） */
export interface CostOverviewVO {
  /** 今日调用次数（DB 明细口径） */
  todayCalls: number
  /** 今日输入 token */
  todayPromptTokens: number
  /** 今日输出 token */
  todayCompletionTokens: number
  /** 今日合计 token */
  todayTokens: number
  /** 当前生效日预算（与降级判定同源，0 表示未设上限） */
  dailyBudget: number
  /** 已用百分比（DB 明细口径，0–100） */
  usedPercent: number
  /** 预算状态：NORMAL / WARN / DEGRADED（按 DB 明细判定） */
  status: string
  /** 已用百分比（Redis 计数桶口径，降级判定实际所用） */
  counterUsagePercent: number
  /** 是否已进入降级（按今日计数桶 / 当前生效预算实时判定，即编排器实际行为） */
  degraded: boolean
  /** 本月累计 token */
  monthTokens: number
}

/** 趋势点（GET /api/cost/trend） */
export interface CostTrendPointVO {
  /** 时间桶：`yyyy-MM-dd` 或 `yyyy-MM-dd HH:00` */
  bucket: string
  /** 调用次数 */
  calls: number
  /** 输入 token */
  promptTokens: number
  /** 输出 token */
  completionTokens: number
  /** 合计 token */
  tokens: number
}

/** 用途分布切片（GET /api/cost/by-purpose） */
export interface CostPurposeSliceVO {
  /** 用途：CHAT / MEMORY_EXTRACT / INTENT（历史遗留值原样透出） */
  purpose: string
  /** 调用次数 */
  calls: number
  /** 合计 token */
  tokens: number
  /** 占区间总 token 百分比（0–100，一位小数） */
  percent: number
}

/** 模型分布切片（GET /api/cost/by-model） */
export interface CostModelSliceVO {
  /** 供应商标识 */
  provider: string
  /** 模型名；null 表示未显式指定、走供应商默认 */
  model: string | null
  /** 调用次数 */
  calls: number
  /** 合计 token */
  tokens: number
  /** 占比 */
  percent: number
}

/** 用户消耗排行项（GET /api/cost/top-users） */
export interface CostUserSliceVO {
  /**
   * 脱敏后的用户标识。
   *
   * 后端按 BR-21 在**写入侧**已脱敏，此处原样透出；前端不得再次打码
   * （二次打码会把 `oabc****wxyz` 再削一次，得到错误标识）。
   */
  openid: string
  /** 调用次数 */
  calls: number
  /** 合计 token */
  tokens: number
}

/** 明细行（GET /api/cost/recent，聚合数值的取证入口） */
export interface CostCallDetailVO {
  /** 主键 */
  id: number
  /** 用途 */
  purpose: string
  /** 供应商 */
  provider: string
  /** 模型名（可空） */
  model: string | null
  /** 脱敏用户标识（可空） */
  openid: string | null
  /** 会话 id（可空） */
  sessionId: number | null
  /** 链路标识（可空） */
  traceId: string | null
  /** 输入 token */
  promptTokens: number
  /** 输出 token */
  completionTokens: number
  /** 合计 token */
  totalTokens: number
  /** 调用时间 */
  createdAt: string | null
}

/** 带条数的区间查询（top-users / recent 用） */
export interface CostTopQuery extends CostRangeQuery {
  /** 返回条数（后端夹紧到 1~50） */
  limit?: number
}

/** 趋势查询（附加粒度） */
export interface CostTrendQuery extends CostRangeQuery {
  /** 粒度：DAY 按天（缺省），HOUR 按小时 */
  granularity?: 'DAY' | 'HOUR'
}
