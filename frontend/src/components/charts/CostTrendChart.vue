<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { CostTrendPointVO } from '@/types/cost'

/**
 * token 消耗趋势（B-4 / W5：堆叠柱 + 调用量折线）。
 *
 * 口径：全部取自 `log_llm_call` 明细聚合，输入/输出堆叠后即为合计，
 * 与同页趋势表逐桶一致（FR-17 AC①）。数据来自 `/api/cost/trend`。
 */
const props = defineProps<{
  /** 趋势点（按时间正序） */
  points: CostTrendPointVO[]
}>()

const option = computed<EChartsOption>(() => {
  const buckets = props.points.map((point) => point.bucket)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['输入 token', '输出 token', '调用次数'] },
    grid: { left: 64, right: 56, top: 40, bottom: 32 },
    xAxis: { type: 'category', data: buckets, axisLabel: { hideOverlap: true } },
    yAxis: [
      { type: 'value', name: 'token' },
      { type: 'value', name: '调用次数', minInterval: 1 },
    ],
    series: [
      {
        name: '输入 token',
        type: 'bar',
        stack: 'tokens',
        data: props.points.map((point) => point.promptTokens),
        itemStyle: { color: '#409eff' },
      },
      {
        name: '输出 token',
        type: 'bar',
        stack: 'tokens',
        data: props.points.map((point) => point.completionTokens),
        itemStyle: { color: '#79bbff' },
      },
      {
        name: '调用次数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: props.points.map((point) => point.calls),
        itemStyle: { color: '#67c23a' },
      },
    ],
  }
})
</script>

<template>
  <EChartBase :option="option" />
</template>
