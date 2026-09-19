import { request } from '@/utils/api'
import type { PageResult } from '@/types/api'
import type { UserDetailVO, UserProfileUpdateRequest, UserQuery, UserStatusRequest, UserVO } from '@/types/user'
import { STORAGE_KEYS } from '@/utils/constants'

/**
 * 用户域 API（9.3：一域一文件）。
 *
 * 基础路径 `/api` 由 axios 单例统一提供，故此处 url 相对其书写。
 */
export const userApi = {
  /** 分页查询用户（GET /api/users） */
  list(params: UserQuery): Promise<PageResult<UserVO>> {
    return request<PageResult<UserVO>>({ url: '/users', method: 'get', params })
  },

  /** 用户详情（GET /api/users/{id}） */
  detail(id: number): Promise<UserDetailVO> {
    return request<UserDetailVO>({ url: `/users/${id}`, method: 'get' })
  },

  /** 启用/禁用用户（PUT /api/users/{id}/status，SUPER_ADMIN） */
  updateStatus(id: number, payload: UserStatusRequest): Promise<null> {
    return request<null>({ url: `/users/${id}/status`, method: 'put', data: payload })
  },

  /**
   * 维护用户档案（PUT /api/users/{id}/profile，SUPER_ADMIN/OPERATOR）。
   *
   * 迭代 2 T11：变更前后值写入 log_audit（FR-16 AC③）。
   */
  updateProfile(id: number, payload: UserProfileUpdateRequest): Promise<null> {
    return request<null>({ url: `/users/${id}/profile`, method: 'put', data: payload })
  },

  /**
   * 导出用户 CSV（GET /api/users/export，SUPER_ADMIN）。
   *
   * 文件下载走原生 fetch：axios 单例的响应拦截器按 `ApiResponse` 拆包，不适用于 blob 响应。
   *
   * @param params 查询条件
   * @returns CSV 文件 Blob
   */
  async exportCsv(params: UserQuery): Promise<Blob> {
    const base = import.meta.env.VITE_API_BASE_URL || '/api'
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        search.append(key, String(value))
      }
    })
    const response = await fetch(`${base}/users/export?${search.toString()}`, {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      throw new Error('导出失败')
    }
    return response.blob()
  },
}
