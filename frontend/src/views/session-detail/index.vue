<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { sessionApi } from '@/services/session.api'
import { useToastStore } from '@/stores/toast'
import type { MessageVO } from '@/types/session'
import { MESSAGE_ROLE_LABELS, MESSAGE_TYPE_LABELS, SEND_STATUS_LABELS } from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 会话消息流（T05 / 架构 4.3）。支持 role / msgType 过滤。
 */
const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const sessionId = Number(route.params.id)
const filters = reactive<{ role?: string; msgType?: string }>({ role: undefined, msgType: undefined })
const messages = ref<MessageVO[]>([])
const loading = ref(false)

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

onMounted(load)
</script>

<template>
  <section>
    <el-page-header
      :content="`会话 #${sessionId} 消息流`"
      @back="router.back()"
    />

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
.filters {
  margin: 16px 0 12px;
}
</style>
