<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { IntentSliceVO } from '@/types/monitor'

/**
 * 意图分布（FR-17 ④：饼图 / T4）。
 *
 * 口径：按工具调用归因到业务意图域（image_recognition/tts/express/navigation/pet_profile/chat）。
 * 数据来自 `/api/monitor/intent-distribution`。
 */
const props = defineProps<{
  /** 意图分布切片 */
  slices: IntentSliceVO[]
}>()

/** 意图域中文名（对齐 FR-05 意图域标签） */
const INTENT_LABELS: Record<string, string> = {
  image_recognition: '图片识别',
  tts: '语音合成',
  express: '快递查询',
  navigation: '导航',
  pet_profile: '宠物档案',
  chat: '纯对话',
}

const option = computed<EChartsOption>(() => {
  const data = props.slices
    .filter((s) => s.count > 0)
    .map((s) => ({ name: INTENT_LABELS[s.intent] ?? s.intent, value: s.count }))
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        name: '意图分布',
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
