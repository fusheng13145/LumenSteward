<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChartBase from '@/components/charts/EChartBase.vue'
import type { SpanStatus, TraceWaterfallVO } from '@/types/monitor'

/**
 * 单次链路时序瀑布图（A-5 超时预算 / T6）。
 *
 * 以「水平堆叠条形」实现甘特式瀑布：底层透明条承担相对链路起点的偏移（startOffsetMs），
 * 上层彩条承担持续耗时（durationMs），颜色编码执行状态；红色虚线标注总预算（SC-03）。
 * 数据来自 `GET /api/monitor/trace/{traceId}`。
 */
const props = defineProps<{
  /** 链路时序瀑布数据 */
  trace: TraceWaterfallVO
}>()

/** span 状态 → 颜色（OK 绿 / DEGRADED 橙 / FAIL·TIMEOUT 红） */
const STATUS_COLORS: Record<SpanStatus, string> = {
  OK: '#67c23a',
  DEGRADED: '#e6a23c',
  FAIL: '#f56c6c',
  TIMEOUT: '#c45656',
}

function colorOf(status: SpanStatus): string {
  return STATUS_COLORS[status] ?? '#909399'
}

const option = computed<EChartsOption>(() => {
  const spans = props.trace.spans ?? []
  const categories = spans.map((span) => `${span.seq}. ${span.name}`)
  // 底层：起始偏移（透明），使彩条从相对偏移处开始
  const offsets = spans.map((span) => span.startOffsetMs)
  // 上层：持续耗时（按状态着色）
  const bars = spans.map((span) => ({
    value: span.durationMs,
    itemStyle: { color: colorOf(span.status) },
  }))
  const axisMax = Math.max(props.trace.totalBudgetMs, props.trace.totalMs) + 50

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown): string => {
        const index = (params as { dataIndex: number }).dataIndex
        const span = spans[index]
        if (!span) {
          return ''
        }
        const kind = span.kind === 'TOOL' ? '工具调用' : 'LLM 轮次'
        return [
          `<b>${span.name}</b>（${kind} · 第 ${span.round} 轮）`,
          `开始偏移：${span.startOffsetMs} ms`,
          `耗时：${span.durationMs} ms`,
          `状态：${span.status}`,
        ].join('<br/>')
      },
    },
    grid: { left: 170, right: 40, top: 24, bottom: 36 },
    xAxis: { type: 'value', name: 'ms', max: axisMax, min: 0 },
    yAxis: { type: 'category', data: categories, inverse: true, axisTick: { show: false } },
    series: [
      {
        name: '偏移',
        type: 'bar',
        stack: 'trace',
        silent: true,
        itemStyle: { color: 'transparent' },
        emphasis: { disabled: true },
        data: offsets,
        markLine: {
          symbol: 'none',
          silent: true,
          lineStyle: { type: 'dashed', color: '#f56c6c' },
          label: { formatter: '总预算', position: 'end' },
          data: [{ xAxis: props.trace.totalBudgetMs }],
        },
      },
      {
        name: '耗时',
        type: 'bar',
        stack: 'trace',
        barWidth: 14,
        data: bars,
      },
    ],
  }
})
</script>

<template>
  <EChartBase
    :option="option"
    height="360px"
  />
</template>
