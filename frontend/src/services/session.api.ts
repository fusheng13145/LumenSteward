import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type { MessageVO, SessionQuery, SessionVO } from '@/types/session'

/**
 * 会话与消息域 API（9.3：一域一文件）。
 */
export const sessionApi = {
  /** 会话列表（GET /api/sessions，按 last_active_at 倒序） */
  list(params: SessionQuery): Promise<PageResult<SessionVO>> {
    return request<PageResult<SessionVO>>({ url: '/sessions', method: 'get', params })
  },

  /** 会话消息流（GET /api/sessions/{id}/messages） */
  messages(id: number, params?: { role?: string; msgType?: string }): Promise<MessageVO[]> {
    return request<MessageVO[]>({ url: `/sessions/${id}/messages`, method: 'get', params })
  },
}
