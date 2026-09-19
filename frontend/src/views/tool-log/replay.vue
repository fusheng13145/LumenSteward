<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { toolLogApi } from '@/services/toolLog.api'
import { useToastStore } from '@/stores/toast'
import type { ReplayRequest, ReplayResultVO } from '@/types/toolLog'
import { REPLAY_SKIP_REASON_LABELS, REPLAY_STATUS_LABELS } from '@/utils/constants'

/**
 * 工具调用回放调试台（A-2 / 架构 4.3 / 迭代 2 T12）。
 *
 * 两条能力：
 * 1. **干跑重放（AC①）**：按 traceId（或单条日志 id）用当前工具实现重跑历史入参，
 *    历史状态与本次状态并列展示，用于验证「工具修好没有」。
 * 2. **复现拦截（AC②）**：填入当时的回复文本，重跑执行一致性校验，复现
 *    「模型声称成功但工具失败」被拦截的判定路径。
 *
 * 默认开启干跑：非只读工具不实际执行，避免调试动作改写业务数据。
 */
const route = useRoute()
const toast = useToastStore()

const traceId = ref('')
const logId = ref<number | null>(null)
const reply = ref('')
const dryRun = ref(true)
const loading = ref(false)
const result = ref<ReplayResultVO | null>(null)

/** 状态中文名（未知状态原样展示，不臆造标签） */
function statusLabel(status: string): string {
  return REPLAY_STATUS_LABELS[status] ?? status
}

function skipLabel(reason: string | null): string {
  if (!reason) {
    return '—'
  }
  return REPLAY_SKIP_REASON_LABELS[reason] ?? reason
}

onMounted(() => {
  const queryTraceId = route.query.traceId
  if (typeof queryTraceId === 'string' && queryTraceId) {
    traceId.value = queryTraceId
  }
})

async function run(): Promise<void> {
  if (!traceId.value.trim() && logId.value === null) {
    toast.error('请填写 traceId 或日志 id')
    return
  }
  loading.value = true
  try {
    const payload: ReplayRequest = { dryRun: dryRun.value }
    if (reply.value.trim()) {
      payload.reply = reply.value.trim()
    }
    result.value =
      logId.value !== null
        ? await toolLogApi.replayOne(logId.value, payload)
        : await toolLogApi.replayByTrace({ ...payload, traceId: traceId.value.trim() })
  } catch (error) {
    result.value = null
    toast.error(error instanceof Error ? error.message : '回放失败')
  } finally {
    loading.value = false
  }
}

function reset(): void {
  traceId.value = ''
  logId.value = null
  reply.value = ''
  dryRun.value = true
  result.value = null
}
</script>

<template>
  <section v-loading="loading">
    <h2>工具调用回放调试台</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="tip"
      title="回放以「不制造新事实」为前提"
      description="默认开启干跑：非只读工具（写档案、下发语音等）会被跳过，避免调试动作改写业务数据；填入回复文本可复现执行一致性拦截。"
    />

    <el-form
      class="form"
      label-width="96px"
      @submit.prevent
    >
      <el-form-item label="traceId">
        <el-input
          v-model="traceId"
          placeholder="整条链路回放：填写链路标识"
          clearable
        />
      </el-form-item>
      <el-form-item label="日志 id">
        <el-input-number
          v-model="logId"
          :min="1"
          :controls="false"
          placeholder="单条回放（留空则按 traceId）"
        />
      </el-form-item>
      <el-form-item label="回复文本">
        <el-input
          v-model="reply"
          type="textarea"
          :rows="2"
          placeholder="可选：填入当时的回复文本以复现一致性拦截"
        />
      </el-form-item>
      <el-form-item label="干跑">
        <el-switch
          v-model="dryRun"
          active-text="跳过非只读工具"
          inactive-text="实际执行"
        />
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :loading="loading"
          @click="run"
        >
          执行回放
        </el-button>
        <el-button @click="reset">
          重置
        </el-button>
      </el-form-item>
    </el-form>

    <template v-if="result">
      <el-descriptions
        :column="3"
        border
        size="small"
        class="meta"
      >
        <el-descriptions-item label="链路标识">
          {{ result.traceId }}
        </el-descriptions-item>
        <el-descriptions-item label="用户">
          {{ result.openid || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="模式">
          <el-tag
            :type="result.dryRun ? 'info' : 'warning'"
            size="small"
          >
            {{ result.dryRun ? '干跑' : '实际执行' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="result.consistency.replayed"
        :type="result.consistency.intercepted ? 'error' : 'success'"
        :closable="false"
        show-icon
        class="tip"
        :title="
          result.consistency.intercepted
            ? `已复现拦截：${result.consistency.reason ?? '一致性校验未通过'}`
            : '一致性校验通过（未复现出拦截）'
        "
        :description="result.consistency.claimText ? `命中声明：${result.consistency.claimText}` : ''"
      />

      <el-table
        :data="result.items"
        border
        size="small"
      >
        <el-table-column
          prop="logId"
          label="日志 id"
          width="90"
        />
        <el-table-column
          prop="toolName"
          label="工具名"
          min-width="150"
        />
        <el-table-column
          label="历史状态"
          width="110"
        >
          <template #default="{ row }">
            {{ statusLabel(row.originalStatus) }}
          </template>
        </el-table-column>
        <el-table-column
          label="本次状态"
          width="110"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.replayStatus === 'SUCCESS' ? 'success' : 'warning'"
              size="small"
            >
              {{ statusLabel(row.replayStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="跳过原因"
          min-width="180"
        >
          <template #default="{ row }">
            {{ row.skipped ? skipLabel(row.skipReason) : '—' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="latencyMs"
          label="耗时(ms)"
          width="100"
        />
        <el-table-column
          label="说明 / 结果"
          min-width="260"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.dataJson || row.message || '—' }}
          </template>
        </el-table-column>
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.tip {
  margin: 12px 0;
}

.form {
  max-width: 720px;
}

.meta {
  margin-bottom: 12px;
}
</style>
