<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { PERMISSIONS } from '@/config/permissions'
import { configApi } from '@/services/config.api'
import { useConfigStore } from '@/stores/config'
import { useToastStore } from '@/stores/toast'
import type { ConfigVO } from '@/types/config'

/**
 * 系统配置在线维护（T05 骨架 → 迭代 2 T10：FR-18 在线配置）。
 *
 * 顶部标识当前运行模式（Mock/Real，AC-D3 零代码切换）。列表支持<b>在线编辑</b>：
 * 提交后后端按值类型校验（非法值被拒）、落库并失效缓存，因此<b>免重启生效</b>（AC①）。
 * `SECRET` 值由后端脱敏为尾号（AC②），变更前须填原因并写入审计（AC③）。
 */
const configStore = useConfigStore()
const toast = useToastStore()
const loading = ref(false)
const submitting = ref(false)

const dialogVisible = ref(false)
const editing = ref<ConfigVO | null>(null)
const form = reactive<{ configValue: string; reason: string }>({
  configValue: '',
  reason: '',
})

/** 是否为密钥类（输入不回显明文） */
function isSecret(row: ConfigVO): boolean {
  return row.valueType === 'SECRET' || row.encrypted
}

/** 新值输入框提示：按值类型给出可提交写法（非法值由后端拒绝，此处仅提示） */
const valuePlaceholder = computed(() => {
  const row = editing.value
  if (!row) {
    return '新值'
  }
  if (row.valueType === 'JSON') {
    return 'JSON 数组，如 ["plan_route"]'
  }
  if (row.valueType === 'BOOL') {
    return 'true / false'
  }
  if (row.valueType === 'INT') {
    return '整数'
  }
  return '新值'
})

async function load(): Promise<void> {
  loading.value = true
  try {
    await configStore.load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '配置加载失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row: ConfigVO): void {
  editing.value = row
  // 密钥类不回填明文（后端只给尾号），留空表示不修改
  form.configValue = isSecret(row) ? '' : (row.configValue ?? '')
  form.reason = ''
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  const row = editing.value
  if (!row) {
    return
  }
  if (!form.reason.trim()) {
    toast.error('变更原因必填（BR-25）')
    return
  }
  submitting.value = true
  try {
    await configApi.update({
      reason: form.reason.trim(),
      items: [{ configKey: row.configKey, configValue: form.configValue }],
    })
    toast.success('配置已更新并即时生效（免重启）')
    dialogVisible.value = false
    await load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '配置更新失败')
  } finally {
    submitting.value = false
  }
}

async function resetOne(row: ConfigVO): Promise<void> {
  try {
    await configApi.reset(row.configKey)
    toast.success(`已恢复默认值：${row.configKey}`)
    await load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '恢复默认失败')
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <h2>系统配置</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mode"
      :title="`运行模式：LLM=${configStore.llmProvider || '未加载'} · 微信通道=${configStore.wechatMockEnabled ? 'Mock' : 'Real'}`"
      description="在线配置：改值后即写入 sys_config 并失效缓存，下一次读取即新值，无需重启（FR-18 AC①）。非法值由后端拒绝；SECRET 值仅返回尾号。"
    />

    <el-button
      class="refresh"
      :loading="loading"
      @click="load"
    >
      刷新
    </el-button>

    <el-table
      :data="configStore.persistedItems"
      border
      size="small"
    >
      <el-table-column
        prop="configKey"
        label="键名"
        min-width="200"
      />
      <el-table-column
        prop="configValue"
        label="值"
        min-width="160"
        show-overflow-tooltip
      />
      <el-table-column
        prop="valueType"
        label="类型"
        width="100"
      />
      <el-table-column
        prop="category"
        label="分类"
        width="110"
      />
      <el-table-column
        prop="description"
        label="说明"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column
        label="加密"
        width="80"
      >
        <template #default="{ row }">
          <el-tag
            :type="row.encrypted ? 'warning' : 'info'"
            size="small"
          >
            {{ row.encrypted ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="更新时间"
        width="170"
      >
        <template #default="{ row }">
          {{ row.updatedAt ? dayjs(row.updatedAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="160"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            v-permission="PERMISSIONS.CONFIG_WRITE"
            link
            type="primary"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-permission="PERMISSIONS.CONFIG_WRITE"
            link
            @click="resetOne(row)"
          >
            恢复默认
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      title="修改配置"
      width="520px"
    >
      <el-form
        v-if="editing"
        label-width="96px"
        @submit.prevent
      >
        <el-form-item label="键名">
          <span>{{ editing.configKey }}</span>
        </el-form-item>
        <el-form-item label="当前值">
          <span>{{ isSecret(editing) ? '（密钥，不展示明文）' : (editing.configValue || '—') }}</span>
        </el-form-item>
        <el-form-item label="类型">
          <span>{{ editing.valueType }}</span>
        </el-form-item>
        <el-form-item label="新值">
          <el-input
            v-model="form.configValue"
            :type="isSecret(editing) ? 'password' : 'text'"
            :placeholder="valuePlaceholder"
            show-password
          />
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="2"
            placeholder="必填（BR-25），随变更写入审计"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="submit"
        >
          提交
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.mode {
  margin: 12px 0;
}

.refresh {
  margin-bottom: 12px;
}
</style>
