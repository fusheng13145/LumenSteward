import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type { AuditLogQuery, AuditLogVO } from '@/types/audit'

/**
 * 审计日志域 API（9.3：一域一文件；AUDITOR+）。
 */
export const auditApi = {
  /** 审计日志检索（GET /api/audit-logs） */
  list(params: AuditLogQuery): Promise<PageResult<AuditLogVO>> {
    return request<PageResult<AuditLogVO>>({ url: '/audit-logs', method: 'get', params })
  },
}
