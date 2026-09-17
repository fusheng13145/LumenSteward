import { defineStore } from 'pinia'
import { ElMessage, type MessageHandler } from 'element-plus'

/**
 * 统一反馈通道（9.3 / G-27 相关）。
 *
 * 业务代码调用 `toast.error(msg)` 等，内部转调 Element Plus 的 `ElMessage`，但**保留 store 这层抽象**，
 * 避免业务直接依赖 UI 库 API（便于替换与单测）。
 */
export const useToastStore = defineStore('toast', {
  actions: {
    /** 错误反馈 */
    error(message: string): MessageHandler {
      return ElMessage.error(message)
    },
    /** 成功反馈 */
    success(message: string): MessageHandler {
      return ElMessage.success(message)
    },
    /** 警告反馈 */
    warning(message: string): MessageHandler {
      return ElMessage.warning(message)
    },
    /** 普通信息 */
    info(message: string): MessageHandler {
      return ElMessage.info(message)
    },
  },
})
