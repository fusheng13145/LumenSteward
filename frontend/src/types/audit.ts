import type { PageQuery } from '@/types/api'

/**
 * 审计日志域类型（与后端 `interfaces/dto/audit/*` 同源）。
 */

/** 审计日志视图 */
export interface AuditLogVO {
  /** 主键 */
  id: number
  /** 操作人（系统任务为 null） */
  adminId: number | null
  /** 资源类型：CONFIG/USER/PROFILE/AUTH/DATA_DELETE */
  regType: string
  /** 操作标识 */
  action: string
  /** 操作对象（脱敏标识） */
  target: string | null
  /** 变更前值（已脱敏） */
  beforeValue: string | null
  /** 变更后值（已脱敏） */
  afterValue: string | null
  /** 变更原因 */
  reason: string | null
  /** 来源 IP */
  ip: string | null
  /** 结果：0-失败 1-成功 */
  result: number
  /** 创建时间 */
  createdAt: string | null
}

/** 审计日志查询条件 */
export interface AuditLogQuery extends PageQuery {
  /** 资源类型 */
  regType?: string
  /** 操作标识 */
  action?: string
  /** 下界（ISO 8601） */
  startTime?: string
  /** 上界（ISO 8601） */
  endTime?: string
}
