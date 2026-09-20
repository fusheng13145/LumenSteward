/**
 * 控制台实时事件流类型（FR-08 / 迭代 3 Wave 2 T3）。
 * 与后端 ConsoleEventVO / ConsoleEventType 同源。
 */

/** 事件类型（与后端 application.console.ConsoleEventType 对齐） */
export type ConsoleEventType = 'TOOL_START' | 'TOOL_END' | 'MESSAGE_DELTA' | 'ERROR' | 'DONE'

/** 控制台事件视图（与后端 interfaces.dto.console.ConsoleEventVO 字段一致） */
export interface ConsoleEventVO {
  /** 全局序号（与 SSE id 一致，支撑 Last-Event-ID 续传） */
  id: number
  /** 事件类型 */
  type: ConsoleEventType
  /** 链路标识 */
  traceId: string
  /** 会话 id（可空） */
  sessionId: number | null
  /** 脱敏后的 openid（可空） */
  openid: string | null
  /** 工具名（可空） */
  toolName: string | null
  /** 链路内调用序号（可空） */
  callSeq: number | null
  /** Agent Loop 轮次（可空） */
  round: number | null
  /** 事件时间戳（epoch ms） */
  ts: number
  /** 可选载荷 JSON（可空） */
  payload: string | null
}

/** SSE 连接状态（供前端展示） */
export type ConsoleConnectionStatus = 'connecting' | 'open' | 'reconnecting' | 'closed'
