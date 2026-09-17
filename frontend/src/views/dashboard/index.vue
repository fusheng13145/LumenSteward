<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { dashboardApi } from '@/services/dashboard.api'
import { doctorApi } from '@/services/doctor.api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import type { DashboardSummaryVO } from '@/types/dashboard'
import type { DoctorReportVO } from '@/types/doctor'
import { ROLE_LABELS } from '@/utils/constants'

/**
 * 概览看板（T05）。
 *
 * 数值全部来自 `/api/dashboard/summary`（后端 COUNT 聚合，可由 SQL 复核，非估算）；
 * 下方「系统体检」来自 `/api/doctor`（SUP-05）。
 */
const auth = useAuthStore()
const toast = useToastStore()

const summary = ref<DashboardSummaryVO | null>(null)
const report = ref<DoctorReportVO | null>(null)
const loading = ref(false)

const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '—'))
const successRateText = computed(() =>
  summary.value ? `${(summary.value.successRate * 100).toFixed(2)}%` : '—',
)

/** 概览卡片定义（值渲染统一走 format） */
const cards = computed(() => {
  const data = summary.value
  return [
    { key: 'todayMessages', label: '今日消息量', value: data ? String(data.todayMessages) : '—' },
    { key: 'activeUsers', label: '活跃用户数', value: data ? String(data.activeUsers) : '—' },
    { key: 'toolCalls', label: '工具调用量', value: data ? String(data.toolCalls) : '—' },
    { key: 'successRate', label: '工具成功率', value: successRateText.value },
    { key: 'degradedCount', label: '降级次数', value: data ? String(data.degradedCount) : '—' },
  ]
})

/** 体检项连通性 → 标签类型 */
function connectivityTag(value: string): 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'UP') {
    return 'success'
  }
  if (value === 'TIMEOUT') {
    return 'warning'
  }
  if (value === 'DOWN') {
    return 'danger'
  }
  return 'info'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [summaryResult, doctorResult] = await Promise.all([
      dashboardApi.summary(),
      doctorApi.report().catch(() => null),
    ])
    summary.value = summaryResult
    report.value = doctorResult
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '看板数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <h2>概览看板</h2>
    <p class="hint">
      欢迎，{{ auth.displayName || auth.username || '管理员' }}（{{ roleLabel }}）。
      数值均由后端聚合查询直接产出，可用 SQL 复核。
    </p>

    <div class="cards">
      <div v-for="card in cards" :key="card.key" class="card">
        <div class="card__label">{{ card.label }}</div>
        <div class="card__value">{{ card.value }}</div>
      </div>
    </div>

    <h3 class="section-title">系统体检（Startup Doctor）</h3>
    <el-alert
      v-if="report"
      :type="report.overall === 'UP' ? 'success' : report.overall === 'DOWN' ? 'error' : 'warning'"
      :title="`整体结论：${report.overall}`"
      :description="`体检时间：${dayjs(report.checkedAt).format('YYYY-MM-DD HH:mm:ss')}`"
      :closable="false"
      show-icon
    />
    <el-table v-if="report" :data="report.items" border size="small" class="doctor-table">
      <el-table-column prop="name" label="依赖" min-width="110" />
      <el-table-column label="连通性" width="110">
        <template #default="{ row }">
          <el-tag :type="connectivityTag(row.connectivity)" size="small">{{ row.connectivity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="mode" label="模式" width="100" />
      <el-table-column prop="configConclusion" label="配置校验" min-width="160" show-overflow-tooltip />
      <el-table-column prop="detail" label="说明" min-width="180" show-overflow-tooltip />
    </el-table>

    <el-button class="refresh" :loading="loading" @click="load">刷新</el-button>
  </section>
</template>

<style scoped>
.hint {
  color: #909399;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin: 16px 0;
}

.card {
  padding: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.card__label {
  color: #909399;
  font-size: 13px;
}

.card__value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.section-title {
  margin: 24px 0 12px;
  font-size: 16px;
}

.doctor-table {
  margin-top: 12px;
}

.refresh {
  margin-top: 16px;
}
</style>
