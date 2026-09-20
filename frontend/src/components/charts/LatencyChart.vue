<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { LatencyBucketVO } from '@/types/monitor'

/**
 * 延迟分布（FR-17 ④：直方图 / T4）。
 *
 * 固定桶（ms）：<100 / 100-299 / 300-999 / 1000-2999 / >=3000。数据来自
 * `/api/monitor/latency-distribution`。
 */
const props = defineProps<{
  /** 延迟分布桶 */
  buckets: LatencyBucketVO[]
}>()

const option = computed<EChartsOption>(() => {
  const ranges = props.buckets.map((b) => b.range)
  const counts = props.buckets.map((b) => b.count)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 48, right: 24, top: 24, bottom: 40 },
    xAxis: { type: 'category', name: '耗时(ms)', data: ranges },
    yAxis: { type: 'value', name: '调用数', minInterval: 1 },
    series: [
      {
        name: '调用数',
        type: 'bar',
        barMaxWidth: 48,
        data: counts,
        itemStyle: { color: '#e6a23c' },
      },
    ],
  }
})
</script>

<template>
  <EChartBase :option="option" />
</template>
