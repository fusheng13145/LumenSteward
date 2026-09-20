import { request } from '@/utils/api'
import type {
  DegradeMetricsVO,
  IntentSliceVO,
  LatencyBucketVO,
  MonitorOverviewVO,
  MonitorRangeQuery,
  SuccessRateVO,
  TrendPointVO,
} from '@/types/monitor'

/**
 * 监控看板域 API（FR-17 / T4 + A-3 / T5，9.3：一域一文件）。
 *
 * 会话列表/消息流与工具日志列表/详情/统计复用 `session.api.ts` / `toolLog.api.ts`；
 * 本文件仅承载监控页专属的聚合缺口端点。
 */
export const monitorApi = {
  /** 概览 KPI（GET /api/monitor/overview） */
  overview(): Promise<MonitorOverviewVO> {
    return request<MonitorOverviewVO>({ url: '/monitor/overview', method: 'get' })
  },

  /** 降级与拦截看板（GET /api/monitor/degrade） */
  degrade(params?: MonitorRangeQuery): Promise<DegradeMetricsVO> {
    return request<DegradeMetricsVO>({ url: '/monitor/degrade', method: 'get', params })
  },

  /** 工具调用趋势（GET /api/monitor/trend） */
  trend(params?: MonitorRangeQuery): Promise<TrendPointVO[]> {
    return request<TrendPointVO[]>({ url: '/monitor/trend', method: 'get', params })
  },

  /** 工具成功率（GET /api/monitor/success-rate） */
  successRate(params?: MonitorRangeQuery): Promise<SuccessRateVO[]> {
    return request<SuccessRateVO[]>({ url: '/monitor/success-rate', method: 'get', params })
  },

  /** 意图分布（GET /api/monitor/intent-distribution） */
  intentDistribution(params?: MonitorRangeQuery): Promise<IntentSliceVO[]> {
    return request<IntentSliceVO[]>({ url: '/monitor/intent-distribution', method: 'get', params })
  },

  /** 延迟分布（GET /api/monitor/latency-distribution） */
  latencyDistribution(params?: MonitorRangeQuery): Promise<LatencyBucketVO[]> {
    return request<LatencyBucketVO[]>({ url: '/monitor/latency-distribution', method: 'get', params })
  },
}
