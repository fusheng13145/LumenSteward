import dayjs from 'dayjs'
import { STORAGE_KEYS } from '@/utils/constants'

/**
 * 合规报告域 API（B-5 / W4 前端入口，9.3：一域一文件）。
 *
 * 后端只聚合既有日志/业务表，不接收查询条件，故此处无入参。
 */
export const complianceApi = {
  /**
   * 导出「数据留存与删除执行」合规报告（GET /api/compliance/report，SUPER_ADMIN 独占）。
   *
   * 文件下载走原生 fetch：axios 单例的响应拦截器按 `ApiResponse` 拆包，不适用于文件流。
   *
   * @returns 报告 Blob 与服务端 Content-Disposition 给定的文件名
   */
  async exportReport(): Promise<{ blob: Blob; filename: string }> {
    const base = import.meta.env.VITE_API_BASE_URL || '/api'
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    const response = await fetch(`${base}/compliance/report`, {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      throw new Error(`导出失败（HTTP ${response.status}）`)
    }
    return {
      blob: await response.blob(),
      filename: resolveFilename(response.headers.get('content-disposition')),
    }
  },
}

/** 取服务端文件名；缺失时按同样的命名规则本地兜底 */
function resolveFilename(header: string | null): string {
  const serverName = /filename="?([^";]+)"?/.exec(header ?? '')?.[1]
  return serverName || `compliance-report-${dayjs().format('YYYYMMDD')}.md`
}
