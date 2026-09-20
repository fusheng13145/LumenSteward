import { request } from '@/utils/api'
import type { TaskContextVO } from '@/types/session'

/**
 * 任务型会话域 API（FR-24 / 迭代 3 Wave 2 T8 / 9.3：一域一文件）。
 *
 * 仅提供只读进度查询与手动放弃；任务的建立与槽位填充由后端编排器在用户消息内部驱动。
 */
export const taskApi = {
  /** 任务进度（GET /api/tasks/{sessionId}）；无记录返回 null */
  getTask(sessionId: number): Promise<TaskContextVO | null> {
    return request<TaskContextVO | null>({ url: `/tasks/${sessionId}`, method: 'get' })
  },

  /** 手动放弃任务（POST /api/tasks/{sessionId}/abandon，OPERATOR+） */
  abandon(sessionId: number): Promise<boolean> {
    return request<boolean>({ url: `/tasks/${sessionId}/abandon`, method: 'post' })
  },
}
