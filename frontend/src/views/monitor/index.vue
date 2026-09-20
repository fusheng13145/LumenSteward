<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import IntentChart from '@/components/charts/IntentChart.vue'
import LatencyChart from '@/components/charts/LatencyChart.vue'
import SuccessRateChart from '@/components/charts/SuccessRateChart.vue'
import TrendChart from '@/components/charts/TrendChart.vue'
import { monitorApi } from '@/services/monitor.api'
import { sessionApi } from '@/services/session.api'
import { toolLogApi } from '@/services/toolLog.api'
import { useToastStore } from '@/stores/toast'
import type {
  DegradeMetricsVO,
  IntentSliceVO,
  LatencyBucketVO,
  MonitorOverviewVO,
  SuccessRateVO,
  TrendPointVO,
} from '@/types/monitor'
import type { MessageVO, SessionVO } from '@/types/session'
import type { ToolLogDetailVO, ToolLogVO, ToolStatsVO } from '@/types/toolLog'
import {
  MESSAGE_ROLE_LABELS,
  MESSAGE_TYPE_LABELS,
  PAGE,
  SESSION_STATE,
  TOOL_STATUS_LABELS,
} from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 会话与工具调用监控（FR-17 / T4）+ 降级与拦截看板（A-3 / T5）。
 *
 * 只读监控（BR-23）：概览 KPI、降级与拦截指标、四张 ECharts 统计图（趋势/成功率/意图/延迟）；
 * 会话列表与消息流复用 `/api/sessions`，工具日志列表/详情/统计复用 `/api/tool-logs`。
 * 所有数值均为后端聚合查询直接产出，可用 SQL 复核（AC-E5）。
 */
const toast = useToastStore()

const activeTab = ref<'overview' | 'sessions' | 'tools'>('overview')
const loading = ref(false)

// ===== 时间范围与粒度 =====
const range = ref<[string, string] | null>(null)
const granularity = ref<'DAY' | 'HOUR'>('DAY')

// ===== 概览 / 图表 / 降级 =====
const overview = ref<MonitorOverviewVO | null>(null)
const degrade = ref<DegradeMetricsVO | null>(null)
const trend = ref<TrendPointVO[]>([])
const successRates = ref<SuccessRateVO[]>([])
const intents = ref<IntentSliceVO[]>([])
const latencies = ref<LatencyBucketVO[]>([])

// ===== 会话监控 =====
const sessionPage = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const sessionFilters = reactive<{ openid: string; state?: string }>({ openid: '', state: undefined })
const sessions = ref<SessionVO[]>([])
const sessionTotal = ref(0)
const messageVisible = ref(false)
const messageLoading = ref(false)
const activeSession = ref<SessionVO | null>(null)
const messages = ref<MessageVO[]>([])

// ===== 工具调用 =====
const toolPage = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const toolFilters = reactive<{ toolName: string; status?: number; openid: string }>({
  toolName: '',
  status: undefined,
  openid: '',
})
const toolRows = ref<ToolLogVO[]>([])
const toolTotal = ref(0)
const toolStats = ref<ToolStatsVO | null>(null)
const toolDetailVisible = ref(false)
const toolDetail = ref<ToolLogDetailVO | null>(null)

const STATE_OPTIONS = [
  { label: '空闲', value: SESSION_STATE.IDLE },
  { label: '闲聊中', value: SESSION_STATE.CHATTING },
  { label: '任务执行中', value: SESSION_STATE.TASKING },
  { label: '降级', value: SESSION_STATE.DEGRADED },
]

const STATUS_OPTIONS = [
  { label: '成功', value: 0 },
  { label: '失败', value: 1 },
  { label: '降级', value: 2 },
  { label: '超时', value: 3 },
  { label: '未执行', value: 4 },
]

/** 概览 KPI 卡片 */
const overviewCards = computed(() => {
  const o = overview.value
  return [
    { key: 'todayMessages', label: '今日消息量', value: o ? String(o.todayMessages) : '—' },
    { key: 'activeUsers', label: '活跃用户数', value: o ? String(o.activeUsers) : '—' },
    { key: 'toolCalls', label: '工具调用量', value: o ? String(o.toolCalls) : '—' },
    { key: 'successRate', label: '工具成功率', value: o ? `${(o.successRate * 100).toFixed(2)}%` : '—' },
    { key: 'avgLatencyMs', label: '平均耗时', value: o ? `${o.avgLatencyMs.toFixed(0)} ms` : '—' },
    { key: 'degradedCount', label: '降级次数', value: o ? String(o.degradedCount) : '—' },
  ]
})

/** 降级与拦截 KPI（T5） */
const degradeCards = computed(() => {
  const d = degrade.value
  return [
    { key: 'degradeRate', label: '降级触发率', value: d ? `${(d.degradeRate * 100).toFixed(2)}%` : '—' },
    {
      key: 'degradedCalls',
      label: '降级/超时触发',
      value: d ? `${d.degradedCount} / ${d.timeoutCount}` : '—',
    },
    {
      key: 'hallucination',
      label: '幻觉拦截次数',
      value: d ? String(d.hallucinationInterceptions) : '—',
    },
    {
      key: 'leak',
      label: '对外泄漏（KPI）',
      value: d ? String(d.externalLeakCount) : '—',
    },
  ]
})

function statusTag(status: number): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 0) return 'success'
  if (status === 2) return 'warning'
  if (status === 1 || status === 3) return 'danger'
  return 'info'
}

/** 消息角色 → Element Plus 标签色（user/assistant/tool 分色展示） */
function roleTag(role: string): 'primary' | 'success' | 'warning' | 'info' {
  if (role === 'user') return 'primary'
  if (role === 'assistant') return 'success'
  if (role === 'tool') return 'warning'
  return 'info'
}

/** 共享时间范围参数 */
function rangeParams(): { start?: string; end?: string } {
  return range.value ? { start: range.value[0], end: range.value[1] } : {}
}

async function loadOverview(): Promise<void> {
  const [overviewResult, degradeResult, trendResult, rateResult, intentResult, latencyResult] =
    await Promise.all([
      monitorApi.overview(),
      monitorApi.degrade(rangeParams()),
      monitorApi.trend({ ...rangeParams(), granularity: granularity.value }),
      monitorApi.successRate(rangeParams()),
      monitorApi.intentDistribution(rangeParams()),
      monitorApi.latencyDistribution(rangeParams()),
    ])
  overview.value = overviewResult
  degrade.value = degradeResult
  trend.value = trendResult
  successRates.value = rateResult
  intents.value = intentResult
  latencies.value = latencyResult
}

async function loadSessions(): Promise<void> {
  const result = await sessionApi.list({
    page: sessionPage.page,
    pageSize: sessionPage.pageSize,
    openid: sessionFilters.openid || undefined,
    state: sessionFilters.state,
  })
  sessions.value = result.list
  sessionTotal.value = result.total
}

async function loadTools(): Promise<void> {
  const query = {
    page: toolPage.page,
    pageSize: toolPage.pageSize,
    toolName: toolFilters.toolName || undefined,
    status: toolFilters.status,
    openid: toolFilters.openid || undefined,
    startTime: range.value ? range.value[0] : undefined,
    endTime: range.value ? range.value[1] : undefined,
  }
  const [listResult, statsResult] = await Promise.all([
    toolLogApi.list(query),
    toolLogApi
      .stats({
        toolName: toolFilters.toolName || undefined,
        startTime: query.startTime,
        endTime: query.endTime,
      })
      .catch(() => null),
  ])
  toolRows.value = listResult.list
  toolTotal.value = listResult.total
  toolStats.value = statsResult
}

/** 刷新当前可见内容 */
async function refresh(): Promise<void> {
  loading.value = true
  try {
    await loadOverview()
    if (activeTab.value === 'sessions') {
      await loadSessions()
    }
    if (activeTab.value === 'tools') {
      await loadTools()
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '监控数据加载失败')
  } finally {
    loading.value = false
  }
}

function searchSessions(): void {
  sessionPage.page = PAGE.DEFAULT_PAGE
  void loadSessions().catch((error: unknown) =>
    toast.error(error instanceof Error ? error.message : '会话列表加载失败'),
  )
}

function searchTools(): void {
  toolPage.page = PAGE.DEFAULT_PAGE
  void loadTools().catch((error: unknown) =>
    toast.error(error instanceof Error ? error.message : '工具日志加载失败'),
  )
}

/** 打开会话消息流 */
async function openMessages(row: SessionVO): Promise<void> {
  activeSession.value = row
  messageVisible.value = true
  messageLoading.value = true
  try {
    messages.value = await sessionApi.messages(row.id)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '消息流加载失败')
    messages.value = []
  } finally {
    messageLoading.value = false
  }
}

/** 打开工具日志详情 */
async function openToolDetail(row: ToolLogVO): Promise<void> {
  try {
    toolDetail.value = await toolLogApi.detail(row.id)
    toolDetailVisible.value = true
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '工具日志详情加载失败')
  }
}

async function onTabChange(name: string | number): Promise<void> {
  if (name === 'sessions' && sessions.value.length === 0) {
    await searchSessionsAsync()
  }
  if (name === 'tools' && toolRows.value.length === 0) {
    await searchToolsAsync()
  }
}

async function searchSessionsAsync(): Promise<void> {
  try {
    await loadSessions()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '会话列表加载失败')
  }
}

async function searchToolsAsync(): Promise<void> {
  try {
    await loadTools()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '工具日志加载失败')
  }
}

onMounted(refresh)
</script>

<template>
  <section v-loading="loading">
    <div class="bar">
      <h2>监控看板</h2>
      <el-date-picker
        v-model="range"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss"
        range-separator="~"
        start-placeholder="开始"
        end-placeholder="结束"
      />
      <el-select
        v-model="granularity"
        style="width: 120px"
        @change="refresh"
      >
        <el-option
          label="按天"
          value="DAY"
        />
        <el-option
          label="按小时"
          value="HOUR"
        />
      </el-select>
      <el-button
        type="primary"
        :loading="loading"
        @click="refresh"
      >
        刷新
      </el-button>
    </div>

    <el-tabs
      v-model="activeTab"
      @tab-change="onTabChange"
    >
      <el-tab-pane
        label="概览与统计"
        name="overview"
      >
        <div class="cards">
          <div
            v-for="card in overviewCards"
            :key="card.key"
            class="card"
          >
            <div class="card__label">
              {{ card.label }}
            </div>
            <div class="card__value">
              {{ card.value }}
            </div>
          </div>
        </div>

        <h3 class="section-title">
          降级与拦截看板（A-3）
        </h3>
        <div class="cards">
          <div
            v-for="card in degradeCards"
            :key="card.key"
            class="card"
            :class="{ 'card--kpi': card.key === 'leak' }"
          >
            <div class="card__label">
              {{ card.label }}
            </div>
            <div class="card__value">
              {{ card.value }}
              <el-tag
                v-if="card.key === 'leak' && degrade"
                :type="degrade.leakKpiPass ? 'success' : 'danger'"
                size="small"
              >
                {{ degrade.leakKpiPass ? '达标' : '异常' }}
              </el-tag>
            </div>
          </div>
        </div>

        <el-table
          v-if="degrade"
          :data="degrade.anomalyDistribution"
          border
          size="small"
          class="anomaly"
        >
          <el-table-column
            prop="layer"
            label="层次"
            width="90"
          />
          <el-table-column
            prop="label"
            label="类别"
            min-width="140"
          />
          <el-table-column
            prop="count"
            label="计数"
            width="120"
          />
        </el-table>

        <div class="charts">
          <div class="chart">
            <h4>工具调用趋势</h4>
            <TrendChart :points="trend" />
          </div>
          <div class="chart">
            <h4>工具成功率</h4>
            <SuccessRateChart :items="successRates" />
          </div>
          <div class="chart">
            <h4>意图分布</h4>
            <IntentChart :slices="intents" />
          </div>
          <div class="chart">
            <h4>延迟分布</h4>
            <LatencyChart :buckets="latencies" />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane
        label="会话监控"
        name="sessions"
      >
        <el-form
          class="filters"
          inline
          @submit.prevent="searchSessions"
        >
          <el-form-item label="openid">
            <el-input
              v-model="sessionFilters.openid"
              placeholder="精确匹配"
              clearable
              style="width: 200px"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select
              v-model="sessionFilters.state"
              placeholder="全部"
              clearable
              style="width: 140px"
            >
              <el-option
                v-for="item in STATE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              @click="searchSessions"
            >
              查询
            </el-button>
          </el-form-item>
        </el-form>

        <el-table
          :data="sessions"
          border
          size="small"
        >
          <el-table-column
            prop="id"
            label="ID"
            width="80"
          />
          <el-table-column
            label="openid（脱敏）"
            min-width="150"
          >
            <template #default="{ row }">
              {{ maskOpenid(row.openid) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="state"
            label="状态"
            width="110"
          />
          <el-table-column
            prop="turnCount"
            label="轮次"
            width="90"
          />
          <el-table-column
            label="最后活跃"
            width="170"
          >
            <template #default="{ row }">
              {{ row.lastActiveAt ? dayjs(row.lastActiveAt).format('YYYY-MM-DD HH:mm') : '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="110"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                @click="openMessages(row)"
              >
                消息流
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="sessionTotal"
          :current-page="sessionPage.page"
          :page-size="sessionPage.pageSize"
          @current-change="(value: number) => { sessionPage.page = value; loadSessions() }"
        />
      </el-tab-pane>

      <el-tab-pane
        label="工具调用"
        name="tools"
      >
        <div
          v-if="toolStats"
          class="cards"
        >
          <div class="card">
            <div class="card__label">
              调用总量
            </div>
            <div class="card__value">
              {{ toolStats.total }}
            </div>
          </div>
          <div class="card">
            <div class="card__label">
              成功率
            </div>
            <div class="card__value">
              {{ (toolStats.successRate * 100).toFixed(2) }}%
            </div>
          </div>
          <div class="card">
            <div class="card__label">
              降级数
            </div>
            <div class="card__value">
              {{ toolStats.degraded }}
            </div>
          </div>
          <div class="card">
            <div class="card__label">
              平均耗时
            </div>
            <div class="card__value">
              {{ toolStats.avgLatencyMs.toFixed(0) }} ms
            </div>
          </div>
        </div>

        <el-form
          class="filters"
          inline
          @submit.prevent="searchTools"
        >
          <el-form-item label="工具名">
            <el-input
              v-model="toolFilters.toolName"
              clearable
              style="width: 160px"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select
              v-model="toolFilters.status"
              placeholder="全部"
              clearable
              style="width: 120px"
            >
              <el-option
                v-for="item in STATUS_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="openid">
            <el-input
              v-model="toolFilters.openid"
              clearable
              style="width: 180px"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              @click="searchTools"
            >
              查询
            </el-button>
          </el-form-item>
        </el-form>

        <el-table
          :data="toolRows"
          border
          size="small"
        >
          <el-table-column
            prop="id"
            label="ID"
            width="80"
          />
          <el-table-column
            prop="toolName"
            label="工具"
            width="170"
          />
          <el-table-column
            label="状态"
            width="90"
          >
            <template #default="{ row }">
              <el-tag
                :type="statusTag(row.status)"
                size="small"
              >
                {{ TOOL_STATUS_LABELS[row.status] ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="errorType"
            label="异常层"
            width="120"
          />
          <el-table-column
            prop="fallbackReason"
            label="降级原因"
            min-width="150"
            show-overflow-tooltip
          />
          <el-table-column
            prop="latencyMs"
            label="耗时(ms)"
            width="100"
          />
          <el-table-column
            label="时间"
            width="170"
          >
            <template #default="{ row }">
              {{ row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="90"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                @click="openToolDetail(row)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="toolTotal"
          :current-page="toolPage.page"
          :page-size="toolPage.pageSize"
          @current-change="(value: number) => { toolPage.page = value; loadTools() }"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="messageVisible"
      :title="`会话 #${activeSession?.id ?? ''} 消息流`"
      width="720px"
    >
      <el-table
        v-loading="messageLoading"
        :data="messages"
        border
        size="small"
        height="440"
      >
        <el-table-column
          label="时间"
          width="160"
        >
          <template #default="{ row }">
            {{ row.createdAt ? dayjs(row.createdAt).format('HH:mm:ss') : '—' }}
          </template>
        </el-table-column>
        <el-table-column
          label="角色"
          width="90"
        >
          <template #default="{ row }">
            <el-tag
              :type="roleTag(row.role)"
              size="small"
            >
              {{ MESSAGE_ROLE_LABELS[row.role] ?? row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="类型"
          width="80"
        >
          <template #default="{ row }">
            {{ MESSAGE_TYPE_LABELS[row.msgType] ?? row.msgType }}
          </template>
        </el-table-column>
        <el-table-column
          prop="toolName"
          label="工具"
          width="140"
        />
        <el-table-column
          prop="content"
          label="内容"
          min-width="220"
          show-overflow-tooltip
        />
      </el-table>
    </el-dialog>

    <el-dialog
      v-model="toolDetailVisible"
      title="工具调用详情"
      width="640px"
    >
      <el-descriptions
        v-if="toolDetail"
        :column="2"
        border
        size="small"
      >
        <el-descriptions-item label="traceId">
          {{ toolDetail.traceId }}
        </el-descriptions-item>
        <el-descriptions-item label="工具名">
          {{ toolDetail.toolName }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ TOOL_STATUS_LABELS[toolDetail.status] ?? toolDetail.status }}
        </el-descriptions-item>
        <el-descriptions-item label="异常层">
          {{ toolDetail.errorType || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="耗时(ms)">
          {{ toolDetail.latencyMs ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="Agent 轮次">
          {{ toolDetail.llmRound ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item
          label="降级原因"
          :span="2"
        >
          {{ toolDetail.fallbackReason || '—' }}
        </el-descriptions-item>
        <el-descriptions-item
          label="入参"
          :span="2"
        >
          <pre class="json">{{ toolDetail.paramsJson || '—' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item
          label="结果"
          :span="2"
        >
          <pre class="json">{{ toolDetail.resultJson || '—' }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </section>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.bar h2 {
  margin: 0;
  font-size: 18px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin: 16px 0;
}

.card {
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.card--kpi {
  border-color: #f0c78a;
}

.card__label {
  color: #909399;
  font-size: 13px;
}

.card__value {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
}

.section-title {
  margin: 20px 0 8px;
  font-size: 16px;
}

.anomaly {
  margin-bottom: 12px;
}

.charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}

.chart {
  padding: 12px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.chart h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #303133;
}

.filters {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
}

.json {
  max-height: 160px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
