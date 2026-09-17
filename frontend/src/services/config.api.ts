import { request } from '@/utils/api'
import type { AuditLogVO } from '@/types/audit'
import type { ConfigUpdateRequest, ConfigVO } from '@/types/config'

/**
 * 系统配置域 API（9.3：一域一文件；仅 SUPER_ADMIN）。
 */
export const configApi = {
  /** 配置列表（含 Mock/Real 运行模式项） */
  list(): Promise<ConfigVO[]> {
    return request<ConfigVO[]>({ url: '/configs', method: 'get' })
  },

  /** 批量更新（须 reason；MVP 仅落库不热更新） */
  update(payload: ConfigUpdateRequest): Promise<null> {
    return request<null>({ url: '/configs', method: 'put', data: payload })
  },

  /** 恢复默认值 */
  reset(key: string): Promise<null> {
    return request<null>({ url: `/configs/${encodeURIComponent(key)}/reset`, method: 'post' })
  },

  /** 变更历史 */
  history(key: string): Promise<AuditLogVO[]> {
    return request<AuditLogVO[]>({ url: `/configs/${encodeURIComponent(key)}/history`, method: 'get' })
  },
}
