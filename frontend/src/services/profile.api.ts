import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type { AuditLogVO } from '@/types/audit'
import type { ProfileHistoryQuery } from '@/types/pet'

/**
 * 档案变更留痕域 API（A-4 / T7 / 9.3：一域一文件；OPERATOR/AUDITOR+）。
 */
export const profileApi = {
  /** 档案变更历史（GET /api/profiles/history） */
  getProfileHistory(params: ProfileHistoryQuery): Promise<PageResult<AuditLogVO>> {
    return request<PageResult<AuditLogVO>>({ url: '/profiles/history', method: 'get', params })
  },
}
