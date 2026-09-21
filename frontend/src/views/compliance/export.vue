<script setup lang="ts">
import { ref } from 'vue'
import { complianceApi } from '@/services/compliance.api'
import { useToastStore } from '@/stores/toast'

/**
 * 合规报告导出入口（B-5 / W4 的前端补口，§2.22）。
 *
 * 一键导出「数据留存与删除执行」自证报告（Markdown）。仅 SUPER_ADMIN 可访问
 * （路由与菜单权限 + 后端 @PreAuthorize 独立鉴权）。报告正文中的 openid 已脱敏（BR-21）。
 */
const toast = useToastStore()

const exporting = ref(false)
const lastExport = ref<{ filename: string; at: string } | null>(null)

const REPORT_SECTIONS = [
  '数据保留策略与执行（FR-19 ①）：消息 / 工具日志 180 天，软删档案宽限 30 天',
  '删除与匿名化执行（FR-19 ②）：按 ALL / CHAT / PET 范围聚合，anon_ 前缀计数',
  '审计与运维：管理员操作留痕与登录/配置变更分布',
  '工具调用与异常：调用量、失败率与异常分类分布',
  '限流：触发次数与阈值',
  '编排链路：预算超限与耗时分布',
]

async function exportReport(): Promise<void> {
  exporting.value = true
  try {
    const { blob, filename } = await complianceApi.exportReport()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    link.click()
    URL.revokeObjectURL(url)
    lastExport.value = { filename, at: new Date().toLocaleString() }
    toast.success(`已导出 ${filename}`)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '导出失败')
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <section>
    <h2>合规报告导出</h2>

    <p class="desc">
      聚合既有日志与业务表生成「数据留存与删除执行」自证报告（Markdown），不新增采集、不落新表、不改动任何数据。
      报告内所有 openid 已脱敏（BR-21）。
    </p>

    <h3>报告包含</h3>
    <ul class="sections">
      <li
        v-for="section in REPORT_SECTIONS"
        :key="section"
      >
        {{ section }}
      </li>
    </ul>

    <div class="actions">
      <el-button
        type="primary"
        :loading="exporting"
        @click="exportReport"
      >
        导出合规报告（Markdown）
      </el-button>
      <span
        v-if="lastExport"
        class="last"
      >
        本次会话最近一次：{{ lastExport.filename }}（{{ lastExport.at }}）
      </span>
    </div>
  </section>
</template>

<style scoped>
.desc {
  max-width: 720px;
  margin: 0 0 16px;
  color: #606266;
  line-height: 1.7;
}

.sections {
  max-width: 720px;
  margin: 0 0 24px;
  padding-left: 20px;
  color: #303133;
  line-height: 1.9;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.last {
  font-size: 13px;
  color: #909399;
}
</style>
