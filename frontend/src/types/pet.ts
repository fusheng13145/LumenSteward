import type { PageQuery } from '@/types/api'

/**
 * 宠物档案域类型（与后端 `interfaces/dto/pet/*` 同源）。
 */

/** 宠物档案视图 */
export interface PetVO {
  /** 主键 */
  id: number
  /** 脱敏后的 openid */
  openid: string
  /** 宠物昵称 */
  petName: string
  /** 类型：猫/狗/其他 */
  petType: string | null
  /** 品种 */
  breed: string | null
  /** 性别：公/母/未知 */
  gender: string | null
  /** 生日（yyyy-MM-dd） */
  birthday: string | null
  /** 体重（kg） */
  weightKg: number | null
  /** 性格描述 */
  personality: string | null
  /** 备注 */
  notes: string | null
  /** 创建时间 */
  createdAt: string | null
  /** 更新时间 */
  updatedAt: string | null
}

/** 新增档案请求 */
export interface PetCreateRequest {
  petName: string
  petType?: string
  breed?: string
  gender?: string
  birthday?: string
  weightKg?: number
  personality?: string
  notes?: string
}

/** 更新档案请求（仅非空项生效） */
export interface PetUpdateRequest {
  petType?: string
  breed?: string
  gender?: string
  birthday?: string
  weightKg?: number
  personality?: string
  notes?: string
}

/** 用户宠物查询（不需要分页，占位保持类型一致） */
export type PetQuery = PageQuery
