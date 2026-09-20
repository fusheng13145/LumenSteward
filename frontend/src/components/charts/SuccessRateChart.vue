<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { SuccessRateVO } from '@/types/monitor'

/**
 * 工具成功率（FR-17 ④：柱状图 / T4）。
 *
 * 按工具名展示成功率（%），hover 显示调用量/成功数。数据来自 `/api/monitor/success-rate`。
 */
const props = defineProps<{
  /** 按工具名分组的成功率 */
  items: SuccessRateVO[]
}>()

const option = computed<EChartsOption>(() => {
  const names = props.items.map((i) => i.toolName)
  const rates = props.items.map((i) => Number((i.successRate * 100).toFixed(2)))
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const list = params as Array<{ dataIndex: number }>
        const idx = list[0]?.dataIndex ?? 0
        const item = props.items[idx]
        if (!item) {
          return ''
        }
        return `${item.toolName}<br/>成功数：${item.success} / ${item.total}<br/>成功率：${(
          item.successRate * 100
        ).toFixed(2)}%`
      },
    },
    grid: { left: 48, right: 24, top: 24, bottom: 48 },
    xAxis: { type: 'category', data: names, axisLabel: { interval: 0, rotate: 20 } },
    yAxis: { type: 'value', name: '成功率(%)', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: [
      {
        name: '成功率',
        type: 'bar',
        barMaxWidth: 40,
        data: rates,
        itemStyle: { color: '#409eff' },
      },
    ],
  }
})
</script>

<template>
  <EChartBase :option="option" />
</template>
