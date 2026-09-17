import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type { ToolLogDetailVO, ToolLogQuery, ToolLogVO, ToolStatsVO } from '@/types/toolLog'

/**
 * 工具调用日志域 API（9.3：一域一文件）。
 */
export const toolLogApi = {
  /** 检索工具日志（GET /api/tool-logs） */
  list(params: ToolLogQuery): Promise<PageResult<ToolLogVO>> {
    return request<PageResult<ToolLogVO>>({ url: '/tool-logs', method: 'get', params })
  },

  /** 日志详情（GET /api/tool-logs/{id}，含入参/结果/耗时/降级原因） */
  detail(id: number): Promise<ToolLogDetailVO> {
    return request<ToolLogDetailVO>({ url: `/tool-logs/${id}`, method: 'get' })
  },

  /** 统计（GET /api/tool-logs/stats） */
  stats(params?: { toolName?: string; startTime?: string; endTime?: string }): Promise<ToolStatsVO> {
    return request<ToolStatsVO>({ url: '/tool-logs/stats', method: 'get', params })
  },
}
