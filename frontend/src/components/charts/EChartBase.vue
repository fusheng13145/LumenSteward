<script setup lang="ts">
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import type { ECharts, EChartsOption } from 'echarts'

/**
 * ECharts 基础封装（FR-17 / T4）。
 *
 * 薄封装：仅负责实例生命周期（init/setOption/dispose）与响应式 resize（ResizeObserver 优先，
 * 退化到 window resize），不引入 vue-echarts 等额外依赖。上层通过 `option` 声明式驱动。
 */

const props = withDefaults(
  defineProps<{
    /** ECharts 配置项 */
    option: EChartsOption
    /** 容器高度（CSS 值），默认 300px */
    height?: string
  }>(),
  { height: '300px' },
)

const el = shallowRef<HTMLDivElement | null>(null)
const chart = shallowRef<ECharts | null>(null)
let observer: ResizeObserver | null = null

/** 应用配置（notMerge=true，避免新旧 series 叠影） */
function apply(): void {
  chart.value?.setOption(props.option, true)
}

/** window resize 兜底（无 ResizeObserver 环境） */
function handleWindowResize(): void {
  chart.value?.resize()
}

onMounted(() => {
  if (!el.value) {
    return
  }
  chart.value = echarts.init(el.value)
  apply()
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(el.value)
  } else {
    window.addEventListener('resize', handleWindowResize)
  }
})

watch(() => props.option, apply, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  window.removeEventListener('resize', handleWindowResize)
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div
    ref="el"
    class="echart-base"
    :style="{ height: props.height }"
  />
</template>

<style scoped>
.echart-base {
  width: 100%;
}
</style>
