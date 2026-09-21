import { request } from '@/utils/api'
import type {
  CostCallDetailVO,
  CostModelSliceVO,
  CostOverviewVO,
  CostPurposeSliceVO,
  CostRangeQuery,
  CostTopQuery,
  CostTrendQuery,
  CostTrendPointVO,
  CostUserSliceVO,
} from '@/types/cost'

/**
 * 成本与配额治理看板域 API（B-4 / 迭代 4 W5，9.3：一域一文件）。
 *
 * 六个端点全为只读，且**不提供阈值调整**：日预算与消息长度上限走 `config.api.ts`
 * 的 `/api/configs`（FR-18 免重启生效），读写分离后「谁改了预算」必然留在审计日志里。
 */
export const costApi = {
  /** 概览 KPI（GET /api/cost/overview，固定今日/本月口径，不受区间影响） */
  overview(): Promise<CostOverviewVO> {
    return request<CostOverviewVO>({ url: '/cost/overview', method: 'get' })
  },

  /** token 消耗趋势（GET /api/cost/trend） */
  trend(params?: CostTrendQuery): Promise<CostTrendPointVO[]> {
    return request<CostTrendPointVO[]>({ url: '/cost/trend', method: 'get', params })
  },

  /** 按用途分布（GET /api/cost/by-purpose） */
  byPurpose(params?: CostRangeQuery): Promise<CostPurposeSliceVO[]> {
    return request<CostPurposeSliceVO[]>({ url: '/cost/by-purpose', method: 'get', params })
  },

  /** 按供应商/模型分布（GET /api/cost/by-model） */
  byModel(params?: CostRangeQuery): Promise<CostModelSliceVO[]> {
    return request<CostModelSliceVO[]>({ url: '/cost/by-model', method: 'get', params })
  },

  /** 用户消耗排行（GET /api/cost/top-users，openid 已在写入侧脱敏） */
  topUsers(params?: CostTopQuery): Promise<CostUserSliceVO[]> {
    return request<CostUserSliceVO[]>({ url: '/cost/top-users', method: 'get', params })
  },

  /** 明细抽样（GET /api/cost/recent，用于逐行核对上方聚合值） */
  recent(params?: CostTopQuery): Promise<CostCallDetailVO[]> {
    return request<CostCallDetailVO[]>({ url: '/cost/recent', method: 'get', params })
  },
}
