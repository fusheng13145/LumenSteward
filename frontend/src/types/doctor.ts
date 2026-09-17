/**
 * 系统自检域类型（与后端 `interfaces/dto/doctor/*` 同源）。
 */

/** 单项依赖体检结果 */
export interface DoctorItem {
  /** 依赖名 */
  name: string
  /** 连通性：UP / DOWN / TIMEOUT / N/A */
  connectivity: string
  /** 运行模式：mock / real / 未配置 */
  mode: string
  /** 配置校验结论 */
  configConclusion: string
  /** 补充说明 */
  detail: string
}

/** 结构化体检报告 */
export interface DoctorReportVO {
  /** 整体结论：UP / DEGRADED / DOWN */
  overall: string
  /** 体检时间 */
  checkedAt: string
  /** 各依赖项明细 */
  items: DoctorItem[]
}
