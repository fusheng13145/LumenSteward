<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { sessionApi } from '@/services/session.api'
import { taskApi } from '@/services/task.api'
import { useToastStore } from '@/stores/toast'
import type { MessageVO, TaskContextVO } from '@/types/session'
import { PERMISSIONS } from '@/config/permissions'
import {
  MESSAGE_ROLE_LABELS,
  MESSAGE_TYPE_LABELS,
  SEND_STATUS_LABELS,
  SESSION_STATE,
} from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 会话消息流（T05 / 架构 4.3）+ 任务进度（FR-24 / T8）。
 *
 * 当会话处于 TASKING（或缺参追问建立任务）时，顶部展示「任务进度」：任务类型、槽位填充进度、
 * 待填槽位与过期时间。槽位仅展示名称不展示取值（运单号等敏感值不在前端回显，BR-15/BR-21）。
 */
const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const sessionId = Number(route.params.id)
const filters = reactive<{ role?: string; msgType?: string }>({ role: undefined, msgType: undefined })
const messages = ref<MessageVO[]>([])
const loading = ref(false)

const task = ref<TaskContextVO | null>(null)
const taskLoading = ref(false)

/** 槽位中文名（与后端任务槽位命名同源）。 */
const SLOT_LABELS: Record<string, string> = {
  tracking_no: '快递单号',
  company_code: '快递公司',
  pet_name: '宠物昵称',
  pet_type: '宠物类型',
  action: '操作类型',
}

const ROLE_OPTIONS = [
  { label: '用户', value: 'user' },
  { label: '助手', value: 'assistant' },
  { label: '工具', value: 'tool' },
]
const TYPE_OPTIONS = [
  { label: '文本', value: 'text' },
  { label: '图片', value: 'image' },
  { label: '语音', value: 'voice' },
  { label: '位置', value: 'location' },
  { label: '事件', value: 'event' },
]

const filledSlotNames = computed<string[]>(() =>
  task.value ? Object.keys(task.value.filledSlots ?? {}) : [],
)

const pendingSlots = computed<string[]>(() => {
  if (!task.value) {
    return []
  }
  const filled = new Set(filledSlotNames.value)
  return (task.value.requiredSlots ?? []).filter((slot) => !filled.has(slot))
})

const showTaskPanel = computed<boolean>(
  () => !!task.value && (task.value.state === SESSION_STATE.TASKING || !!task.value.taskType),
)

function slotLabel(slot: string): string {
  return SLOT_LABELS[slot] ?? slot
}

async function load(): Promise<void> {
  loading.value = true
  try {
    messages.value = await sessionApi.messages(sessionId, {
      role: filters.role,
      msgType: filters.msgType,
    })
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '消息加载失败')
  } finally {
    loading.value = false
  }
}

async function loadTask(): Promise<void> {
  taskLoading.value = true
  try {
    task.value = await taskApi.getTask(sessionId)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '任务进度加载失败')
  } finally {
    taskLoading.value = false
  }
}

async function abandonTask(): Promise<void> {
  try {
    await taskApi.abandon(sessionId)
    toast.success('已放弃当前任务')
    await loadTask()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '放弃任务失败')
  }
}

onMounted(() => {
  void load()
  void loadTask()
})
</script>

<template>
  <section>
    <el-page-header
      :content="`会话 #${sessionId} 消息流`"
      @back="router.back()"
    />

    <el-card
      v-if="showTaskPanel"
      v-loading="taskLoading"
      class="task-panel"
      shadow="never"
    >
      <template #header>
        <div class="task-header">
          <span>任务进度</span>
          <el-tag
            :type="task?.state === SESSION_STATE.TASKING ? 'warning' : 'info'"
            size="small"
          >
            {{ task?.state === SESSION_STATE.TASKING ? '任务执行中' : task?.state }}
          </el-tag>
        </div>
      </template>
      <el-descriptions
        :column="2"
        border
        size="small"
      >
        <el-descriptions-item label="任务类型">
          {{ task?.taskType ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="过期时间">
          {{ task?.expireAt ? dayjs(task.expireAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="已填 / 必填">
          {{ filledSlotNames.length }}/{{ task?.requiredSlots.length ?? 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="待填槽位">
          <template v-if="pendingSlots.length">
            <el-tag
              v-for="slot in pendingSlots"
              :key="slot"
              class="slot-tag"
              size="small"
              type="warning"
            >
              {{ slotLabel(slot) }}
            </el-tag>
          </template>
          <span v-else>已齐备</span>
        </el-descriptions-item>
      </el-descriptions>
      <div class="task-actions">
        <el-button
          v-permission="PERMISSIONS.TASK_ABANDON"
          size="small"
          type="danger"
          plain
          @click="abandonTask"
        >
          放弃任务
        </el-button>
      </div>
    </el-card>

    <el-form
      class="filters"
      inline
      @submit.prevent="load"
    >
      <el-form-item label="角色">
        <el-select
          v-model="filters.role"
          placeholder="全部"
          clearable
          style="width: 130px"
        >
          <el-option
            v-for="item in ROLE_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select
          v-model="filters.msgType"
          placeholder="全部"
          clearable
          style="width: 130px"
        >
          <el-option
            v-for="item in TYPE_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          @click="load"
        >
          查询
        </el-button>
      </el-form-item>
    </el-form>

    <el-table
      v-loading="loading"
      :data="messages"
      border
      size="small"
    >
      <el-table-column
        prop="id"
        label="ID"
        width="80"
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
        label="角色"
        width="90"
      >
        <template #default="{ row }">
          {{ MESSAGE_ROLE_LABELS[row.role] ?? row.role }}
        </template>
      </el-table-column>
      <el-table-column
        label="类型"
        width="90"
      >
        <template #default="{ row }">
          {{ MESSAGE_TYPE_LABELS[row.msgType] ?? row.msgType }}
        </template>
      </el-table-column>
      <el-table-column
        prop="toolName"
        label="工具"
        width="150"
      />
      <el-table-column
        prop="content"
        label="内容"
        min-width="240"
        show-overflow-tooltip
      />
      <el-table-column
        label="发送状态"
        width="110"
      >
        <template #default="{ row }">
          {{ row.sendStatus === null ? '—' : (SEND_STATUS_LABELS[row.sendStatus] ?? row.sendStatus) }}
        </template>
      </el-table-column>
      <el-table-column
        label="openid"
        width="150"
      >
        <template #default="{ row }">
          {{ maskOpenid(row.openid) }}
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.task-panel {
  margin-top: 16px;
}

.task-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.slot-tag {
  margin-right: 6px;
}

.task-actions {
  margin-top: 12px;
}

.filters {
  margin: 16px 0 12px;
}
</style>
