<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { subscribeConsole, type ConsoleSubscription } from '@/utils/sse'
import type { ConsoleConnectionStatus, ConsoleEventVO } from '@/types/console'

/**
 * 实时观测台（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * 订阅 /api/sse/console（JWT 经 ?token= 透传，复用 SecurityConfig 鉴权链路）。
 * 展示编排链路事件流（tool_start/tool_end 用工具色、message_delta 用助手色、error 红色、done 标记终态），
 * 显示连接状态与手动重连，保留最近 N 条。BR-11：只读观测，不承载业务写入。
 */

/** 保留最近事件条数 */
const MAX_EVENTS = 200

const events = ref<ConsoleEventVO[]>([])
const status = ref<ConsoleConnectionStatus>('connecting')
const subscription = ref<ConsoleSubscription | null>(null)

function connect(): void {
  subscription.value?.unsubscribe()
  subscription.value = subscribeConsole(
    (event) => {
      events.value.push(event)
      if (events.value.length > MAX_EVENTS) {
        events.value.splice(0, events.value.length - MAX_EVENTS)
      }
    },
    (s) => {
      status.value = s
    },
  )
}

function reconnect(): void {
  connect()
}

function statusType(s: ConsoleConnectionStatus): 'success' | 'warning' | 'info' | 'danger' {
  switch (s) {
    case 'open':
      return 'success'
    case 'reconnecting':
      return 'warning'
    case 'closed':
      return 'info'
    default:
      return 'info'
  }
}

function statusLabel(s: ConsoleConnectionStatus): string {
  return { connecting: '连接中', open: '已连接', reconnecting: '重连中', closed: '已关闭' }[s]
}

function typeTagType(type: string): 'success' | 'warning' | 'info' | 'danger' {
  if (type === 'ERROR') {
    return 'danger'
  }
  if (type === 'DONE') {
    return 'info'
  }
  if (type === 'MESSAGE_DELTA') {
    return 'success'
  }
  return 'warning'
}

function rowClass(data: { row: ConsoleEventVO }): string {
  return `type-${data.row.type.toLowerCase()}`
}

function toolNameOf(event: ConsoleEventVO): string {
  return event.toolName ?? '—'
}

function payloadText(event: ConsoleEventVO): string {
  if (!event.payload) {
    return ''
  }
  try {
    return JSON.stringify(JSON.parse(event.payload))
  } catch {
    return event.payload
  }
}

function timeText(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

onMounted(() => {
  connect()
})

onBeforeUnmount(() => {
  subscription.value?.unsubscribe()
})
</script>

<template>
  <section>
    <div class="bar">
      <h2>实时观测台</h2>
      <el-tag :type="statusType(status)" size="small">{{ statusLabel(status) }}</el-tag>
      <el-button size="small" @click="reconnect">手动重连</el-button>
      <span class="count">事件数：{{ events.length }}</span>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="tip"
      title="只读观测通道"
      description="本页订阅编排链路实时事件（工具起止 / 终态回复 / 结束），用于管理后台观测，不承载业务写入。"
    />

    <el-table
      :data="events"
      border
      size="small"
      height="520"
      class="stream"
      :row-class-name="rowClass"
    >
      <el-table-column prop="id" label="#" width="70" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <el-tag :type="typeTagType(row.type)" size="small">{{ row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="traceId" label="链路" min-width="160" show-overflow-tooltip />
      <el-table-column label="用户" width="140">
        <template #default="{ row }">{{ row.openid || '—' }}</template>
      </el-table-column>
      <el-table-column label="工具" width="140">
        <template #default="{ row }">{{ toolNameOf(row) }}</template>
      </el-table-column>
      <el-table-column label="轮次/序号" width="110">
        <template #default="{ row }">R{{ row.round ?? '-' }} · #{{ row.callSeq ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="载荷" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ payloadText(row) }}</template>
      </el-table-column>
      <el-table-column label="时间" width="110">
        <template #default="{ row }">{{ timeText(row.ts) }}</template>
      </el-table-column>
    </el-table>
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

.count {
  color: #909399;
  font-size: 13px;
}

.tip {
  margin-bottom: 12px;
}

.stream :deep(.type-tool_start) {
  background: #fdf6ec;
}

.stream :deep(.type-tool_end) {
  background: #f0f9eb;
}

.stream :deep(.type-error) {
  background: #fef0f0;
}
</style>
