/**
 * 系统配置域类型（与后端 `interfaces/dto/config/*` 同源）。
 */

/** 系统配置视图（SECRET 值仅尾号） */
export interface ConfigVO {
  /** 键名 */
  configKey: string
  /** 值（SECRET 已脱敏） */
  configValue: string | null
  /** 值类型：STRING/INT/DECIMAL/BOOL/JSON/SECRET */
  valueType: string
  /** 分类：llm/tts/orchestration/security/text/tool/runtime */
  category: string | null
  /** 说明 */
  description: string | null
  /** 是否加密存储 */
  encrypted: boolean
  /** 更新时间 */
  updatedAt: string | null
}

/** 批量更新请求 */
export interface ConfigUpdateRequest {
  /** 变更原因（必填，BR-25） */
  reason: string
  /** 待更新项 */
  items: ConfigUpdateItem[]
}

/** 单项更新 */
export interface ConfigUpdateItem {
  /** 键名 */
  configKey: string
  /** 新值 */
  configValue: string
}
