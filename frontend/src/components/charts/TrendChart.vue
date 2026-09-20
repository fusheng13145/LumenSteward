<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { TrendPointVO } from '@/types/monitor'

/**
 * 工具调用趋势（FR-17 ④：折线图 / T4）。
 *
 * 左轴：调用量；右轴：成功率（%）。数据来自 `/api/monitor/trend`。
 */
const props = defineProps<{
  /** 趋势点（按时间正序） */
  points: TrendPointVO[]
}>()

const option = computed<EChartsOption>(() => {
  const buckets = props.points.map((p) => p.bucket)
  const totals = props.points.map((p) => p.total)
  const rates = props.points.map((p) => Number((p.successRate * 100).toFixed(2)))
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['调用量', '成功率'] },
    grid: { left: 48, right: 56, top: 40, bottom: 32 },
    xAxis: { type: 'category', boundaryGap: false, data: buckets },
    yAxis: [
      { type: 'value', name: '调用量', minInterval: 1 },
      { type: 'value', name: '成功率(%)', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    ],
    series: [
      { name: '调用量', type: 'line', smooth: true, data: totals, itemStyle: { color: '#409eff' } },
      {
        name: '成功率',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: rates,
        itemStyle: { color: '#67c23a' },
      },
    ],
  }
})
</script>

<template>
  <EChartBase :option="option" />
</template>
