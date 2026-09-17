import { request } from '@/utils/api'
import type { DoctorReportVO } from '@/types/doctor'

/**
 * 系统自检域 API（9.3：一域一文件）。
 */
export const doctorApi = {
  /** 结构化体检报告（GET /api/doctor） */
  report(): Promise<DoctorReportVO> {
    return request<DoctorReportVO>({ url: '/doctor', method: 'get' })
  },
}
