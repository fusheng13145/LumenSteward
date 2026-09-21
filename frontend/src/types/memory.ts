import type { PageQuery } from '@/types/api'

/**
 * 个人状态库域类型（迭代 4 W6-b，与后端 `interfaces/dto/memory/*` 同源）。
 */

/** 状态库条目视图 */
export interface MemoryItemVO {
  /** 主键 */
  id: number
  /** 脱敏后的所属用户标识 */
  openid: string
  /** 条目类型：PERSON/PLACE/THING/PREFERENCE/HABIT/FACT */
  kind: string
  /** 实体名 / 偏好键 */
  name: string
  /** 事实正文（派生 PII） */
  content: string | null
  /** 来源方式：AUTO_EXTRACT / TOOL */
  origin: string
  /** 抽取器标识与版本 */
  extractor: string | null
  /** 抽取置信度 0.000~1.000 */
  confidence: number | string | null
  /** 溯源：来源会话 id */
  sourceSessionId: number | null
  /** 溯源：来源链路标识 */
  sourceTraceId: string | null
  /** 状态：ACTIVE / SUPERSEDED */
  status: string
  /** 本条覆盖掉的旧条 id */
  supersedesId: number | null
  /** 累计出现次数 */
  hitCount: number | null
  /** 首次出现时间 */
  firstSeenAt: string | null
  /** 最近出现时间 */
  lastSeenAt: string | null
}

/** 状态库列表查询条件 */
export interface MemoryQuery extends PageQuery {
  /** 用户过滤（原始 openid，精确匹配） */
  openid?: string
  /** 条目类型过滤 */
  kind?: string
  /** 状态过滤 */
  status?: string
}
