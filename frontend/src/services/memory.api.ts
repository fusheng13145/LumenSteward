import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type { MemoryItemVO, MemoryQuery } from '@/types/memory'

/**
 * 个人状态库域 API（W6-b / 9.3：一域一文件）。
 */
export const memoryApi = {
  /** 条目列表（GET /api/memories，按 last_seen_at 倒序，出参 openid 已脱敏） */
  list(params: MemoryQuery): Promise<PageResult<MemoryItemVO>> {
    return request<PageResult<MemoryItemVO>>({ url: '/memories', method: 'get', params })
  },

  /** 纠错删除条目（DELETE /api/memories/{id}，SUPER_ADMIN 独占，逻辑删除留审计） */
  remove(id: number): Promise<null> {
    return request<null>({ url: `/memories/${id}`, method: 'delete' })
  },
}
