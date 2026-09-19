import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type {
  ReplayRequest,
  ReplayResultVO,
  ToolLogDetailVO,
  ToolLogQuery,
  ToolLogVO,
  ToolStatsVO,
} from '@/types/toolLog'

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

  /**
   * 整条链路回放（POST /api/tool-logs/replay，SUPER_ADMIN/OPERATOR）。
   *
   * 给出 reply 时同时重跑执行一致性校验，用于复现「模型声称成功但工具失败」的拦截（A-2 AC②）。
   */
  replayByTrace(payload: ReplayRequest): Promise<ReplayResultVO> {
    return request<ReplayResultVO>({ url: '/tool-logs/replay', method: 'post', data: payload })
  },

  /** 单条回放（POST /api/tool-logs/{id}/replay） */
  replayOne(id: number, payload?: ReplayRequest): Promise<ReplayResultVO> {
    return request<ReplayResultVO>({
      url: `/tool-logs/${id}/replay`,
      method: 'post',
      data: payload ?? {},
    })
  },
}
