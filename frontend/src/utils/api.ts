import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/api'
import { HTTP_STATUS, STORAGE_KEYS } from '@/utils/constants'

/**
 * axios 三段式单例封装（8.2.1 / 9.3 / G-23 / G-24）。
 *
 * ① 请求拦截：注入认证凭据 + 生成/透传 traceId
 * ② 响应拦截：按业务码拆包，非成功码统一转为携带业务码的错误对象
 * ③ 异常拦截：按 HTTP 状态分类处理（401 清凭据跳登录**并保留 redirect 回跳**）
 *
 * 业务组件**禁止**直接调用 axios，一律经此模块。
 */

/** 携带业务码的错误对象（供上层按 code 精确处理） */
export class ApiError extends Error {
  /** 业务状态码（8.5） */
  readonly code: number
  /** HTTP 状态码 */
  readonly httpStatus: number
  /** 链路追踪 ID，便于定位 */
  readonly traceId?: string

  constructor(code: number, message: string, httpStatus: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
    this.traceId = traceId
  }
}

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
})

// ① 请求拦截：注入 Token 与 traceId
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`)
    }
    config.headers.set('X-Trace-Id', generateTraceId())
    return config
  },
  (error: unknown) => Promise.reject(error),
)

// ② 响应拦截：按业务码拆包
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const body = response.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      // 非成功码：转为携带业务码的错误对象，交由调用方或异常拦截处理
      return Promise.reject(
        new ApiError(body.code, body.message, response.status, body.traceId),
      )
    }
    // 成功后向调用方暴露业务数据本身。axios 拦截器签名要求返回 AxiosResponse，
    // 这里做一次运行期无害的窄化转换（实际值即业务 data）。
    return body?.data as unknown as AxiosResponse
  },
  // ③ 异常拦截：按 HTTP 状态分类
  (error: AxiosError<ApiResponse<unknown>>) => {
    const status = error.response?.status ?? 0
    const body = error.response?.data
    handleHttpError(status, body)
    return Promise.reject(
      new ApiError(body?.code ?? status, body?.message ?? error.message, status, body?.traceId),
    )
  },
)

/** 按 HTTP 状态分类给出用户反馈；401 额外执行跳登录（保留 redirect） */
function handleHttpError(status: number, body?: ApiResponse<unknown>): void {
  const message = body?.message
  switch (status) {
    case HTTP_STATUS.UNAUTHORIZED: {
      // 清除本地凭据
      localStorage.removeItem(STORAGE_KEYS.TOKEN)
      const current = window.location.pathname + window.location.search
      // G-24：跳登录须保留 redirect 回跳路径，登录成功后返回原页面
      if (!current.startsWith('/login')) {
        window.location.href = `/login?redirect=${encodeURIComponent(current)}`
      }
      break
    }
    case HTTP_STATUS.FORBIDDEN:
      ElMessage.error(message || '权限不足')
      break
    case HTTP_STATUS.NOT_FOUND:
      ElMessage.error(message || '资源不存在')
      break
    case HTTP_STATUS.TOO_MANY_REQUESTS:
      ElMessage.warning(message || '请求过于频繁，请稍后再试')
      break
    default:
      if (status >= HTTP_STATUS.SERVER_ERROR) {
        ElMessage.error(message || '服务暂不可用，请稍后再试')
      } else {
        ElMessage.error(message || '请求失败')
      }
  }
}

/** 生成 traceId（优先使用 Web Crypto 的 UUID） */
function generateTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`
}

/**
 * 类型化请求方法：返回已拆包的业务数据。
 *
 * @param config axios 请求配置
 * @returns 业务数据
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  // 响应拦截器已按业务码拆包，运行期返回值为业务 data；axios 的类型签名仍标注为 AxiosResponse，
  // 故此处做一次显式窄化转换。
  return (await service.request(config)) as unknown as T
}

export default service
