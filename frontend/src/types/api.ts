/**
 * 跨域共享的 API 基础类型（G-25：同一语义类型全局唯一定义一次）。
 *
 * 分页类型 `PageResult` / `PageQuery` 仅在此定义，业务域不得重复定义（G-10）。
 */

/** 统一响应体（与后端 ApiResponse 同源，8.2.1） */
export interface ApiResponse<T = unknown> {
  /** 业务状态码：0 表示成功 */
  code: number
  /** 提示信息 */
  message: string
  /** 业务数据 */
  data: T
  /** 链路追踪 ID */
  traceId: string
  /** 响应时间（ISO 8601） */
  timestamp: string
}

/** 分页响应（全局唯一，G-10） */
export interface PageResult<T> {
  /** 当前页数据 */
  list: T[]
  /** 总记录数 */
  total: number
  /** 当前页码 */
  page: number
  /** 每页条数 */
  pageSize: number
}

/** 分页请求（全局唯一，G-10） */
export interface PageQuery {
  /** 页码，≥1 */
  page: number
  /** 每页条数，默认 20，上限 100 */
  pageSize: number
}
