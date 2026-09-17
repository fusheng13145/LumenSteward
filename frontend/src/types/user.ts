import type { PageQuery } from '@/types/api'

/**
 * 用户域类型（与后端 `interfaces/dto/user/*` 同源）。
 */

/** 用户视图（列表） */
export interface UserVO {
  /** 主键 */
  id: number
  /** 脱敏后的 openid（前4 + **** + 后4） */
  openid: string
  /** 昵称 */
  nickname: string | null
  /** 状态：1-正常 0-禁用 */
  status: number
  /** 最后交互时间（ISO 8601） */
  lastInteractAt: string | null
  /** 创建时间（ISO 8601） */
  createdAt: string | null
}

/** 用户详情视图 */
export interface UserDetailVO extends UserVO {
  /** 存活宠物档案数 */
  petCount: number
  /** 会话数 */
  sessionCount: number
  /** 工具调用次数 */
  toolCallCount: number
}

/** 用户列表查询条件 */
export interface UserQuery extends PageQuery {
  /** 关键字（openid / nickname 模糊） */
  keyword?: string
  /** 状态过滤 */
  status?: number
  /** 最后交互时间下界（ISO 8601） */
  startTime?: string
  /** 最后交互时间上界（ISO 8601） */
  endTime?: string
}

/** 启用/禁用请求体 */
export interface UserStatusRequest {
  /** 目标状态：1-启用 0-禁用 */
  status: number
}
