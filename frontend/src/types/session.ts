import type { PageQuery } from '@/types/api'

/**
 * 会话与消息域类型（与后端 `interfaces/dto/session/*` 同源）。
 */

/** 会话视图 */
export interface SessionVO {
  /** 主键 */
  id: number
  /** 脱敏后的 openid */
  openid: string
  /** Redis 上下文键（conv:{openid}） */
  contextKey: string
  /** 会话状态：IDLE/CHATTING/TASKING/DEGRADED */
  state: string
  /** 累计轮次 */
  turnCount: number | null
  /** 最后活跃时间 */
  lastActiveAt: string | null
  /** 创建时间 */
  createdAt: string | null
}

/** 消息视图 */
export interface MessageVO {
  /** 主键 */
  id: number
  /** 所属会话 */
  sessionId: number | null
  /** 脱敏后的 openid */
  openid: string
  /** 微信 MsgId */
  msgId: string | null
  /** 角色：user/assistant/tool */
  role: string
  /** 消息类型：text/image/voice/location/event */
  msgType: string
  /** 内容 */
  content: string | null
  /** 工具名（role=tool 时有值） */
  toolName: string | null
  /** 估算 token 数 */
  tokenCount: number | null
  /** 发送状态：0-待发 1-成功 2-失败 */
  sendStatus: number | null
  /** 创建时间 */
  createdAt: string | null
}

/** 会话列表查询条件 */
export interface SessionQuery extends PageQuery {
  /** 用户过滤 */
  openid?: string
  /** 状态过滤 */
  state?: string
}

/** 任务进度视图（GET /api/tasks/{sessionId}，FR-24 / T8） */
export interface TaskContextVO {
  /** 任务类型（触发工具名，如 query_express）；无任务为 null */
  taskType: string | null
  /** 必填槽位（与工具 Schema required 字段名同源） */
  requiredSlots: string[]
  /** 已填槽位（槽位名 → 值） */
  filledSlots: Record<string, string>
  /** 任务过期时刻（ISO 8601） */
  expireAt: string | null
  /** 会话状态：IDLE/CHATTING/TASKING/DEGRADED */
  state: string
}
