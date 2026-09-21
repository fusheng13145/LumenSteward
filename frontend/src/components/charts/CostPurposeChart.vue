<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { CostPurposeSliceVO } from '@/types/cost'
import { LLM_PURPOSE_LABELS } from '@/utils/constants'

/**
 * LLM 调用用途分布（B-4 / W5：饼图，按 token 计）。
 *
 * 口径：一次调用一个用途，占比按 token 而非次数——对话编排常带长上下文与多轮工具回投，
 * 次数分布会低估它的真实成本。数据来自 `/api/cost/by-purpose`。
 */
const props = defineProps<{
  /** 用途分布切片 */
  slices: CostPurposeSliceVO[]
}>()

const option = computed<EChartsOption>(() => {
  const data = props.slices
    .filter((slice) => slice.tokens > 0)
    .map((slice) => ({
      name: LLM_PURPOSE_LABELS[slice.purpose] ?? slice.purpose,
      value: slice.tokens,
    }))
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} token ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        name: '用途分布',
        type: 'pie',
        radius: ['36%', '64%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        label: { formatter: '{b}: {c}' },
        data,
      },
    ],
  }
})
</script>

<template>
  <EChartBase :option="option" />
</template>
