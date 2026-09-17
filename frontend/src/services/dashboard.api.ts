import { request } from '@/utils/api'
import type { DashboardSummaryVO } from '@/types/dashboard'

/**
 * 概览看板域 API（9.3：一域一文件）。
 */
export const dashboardApi = {
  /** 看板汇总（GET /api/dashboard/summary） */
  summary(): Promise<DashboardSummaryVO> {
    return request<DashboardSummaryVO>({ url: '/dashboard/summary', method: 'get' })
  },
}
