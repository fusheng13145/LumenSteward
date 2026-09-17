import { request } from '@/utils/api'
import type { PetCreateRequest, PetUpdateRequest, PetVO } from '@/types/pet'

/**
 * 宠物档案（管理侧）域 API（9.3：一域一文件）。
 */
export const petApi = {
  /** 查询用户全部存活宠物（GET /api/users/{id}/pets） */
  listByUser(userId: number): Promise<PetVO[]> {
    return request<PetVO[]>({ url: `/users/${userId}/pets`, method: 'get' })
  },

  /** 新增档案（POST /api/users/{id}/pets，OPERATOR+） */
  create(userId: number, payload: PetCreateRequest): Promise<PetVO> {
    return request<PetVO>({ url: `/users/${userId}/pets`, method: 'post', data: payload })
  },

  /** 更新档案（PUT /api/pets/{id}，OPERATOR+） */
  update(id: number, payload: PetUpdateRequest): Promise<PetVO> {
    return request<PetVO>({ url: `/pets/${id}`, method: 'put', data: payload })
  },

  /** 软删除档案（DELETE /api/pets/{id}，OPERATOR+） */
  remove(id: number): Promise<null> {
    return request<null>({ url: `/pets/${id}`, method: 'delete' })
  },
}
