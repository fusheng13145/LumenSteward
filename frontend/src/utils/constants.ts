/**
 * 全局常量与枚举（8.1 / 9.3 / B19）。
 *
 * 约定：所有枚举集中于此，**每项带中文注释**，并与后端枚举、7.6.3 字段 COMMENT、8.5 错误码同源。
 * 业务代码禁止散落魔法字符串。
 */

// ===== 本地存储键 =====
export const STORAGE_KEYS = {
  /** JWT 令牌（注意：localStorage 存在 XSS 风险，见 SRS 9.3 约束 2） */
  TOKEN: 'clawbot_token',
  /** 界面偏好（如侧边栏折叠态） */
  UI_PREFERENCES: 'clawbot_ui',
} as const

// ===== HTTP 状态码（与后端 error 分层处理对应） =====
export const HTTP_STATUS = {
  /** 成功 */
  OK: 200,
  /** 参数错误 */
  BAD_REQUEST: 400,
  /** 未认证：清除凭据并跳登录（保留 redirect） */
  UNAUTHORIZED: 401,
  /** 权限不足 */
  FORBIDDEN: 403,
  /** 资源不存在 */
  NOT_FOUND: 404,
  /** 业务冲突 */
  CONFLICT: 409,
  /** 限流/预算超限 */
  TOO_MANY_REQUESTS: 429,
  /** 服务端错误 */
  SERVER_ERROR: 500,
  /** 依赖不可用（LLM/物流等降级） */
  SERVICE_UNAVAILABLE: 503,
} as const

// ===== 角色（与后端 AdminRole 枚举同源，SRS 3.3） =====
export const ROLE = {
  /** 系统管理员：全量读 + 配置写 + 用户禁用 */
  SUPER_ADMIN: 'SUPER_ADMIN',
  /** 运营管理员：读 + 档案写；不可改配置 */
  OPERATOR: 'OPERATOR',
  /** 审计员：日志只读 */
  AUDITOR: 'AUDITOR',
} as const

/** 角色字面量联合类型 */
export type AdminRole = (typeof ROLE)[keyof typeof ROLE]

/** 角色中文名（用于界面展示） */
export const ROLE_LABELS: Record<AdminRole, string> = {
  SUPER_ADMIN: '系统管理员',
  OPERATOR: '运营管理员',
  AUDITOR: '审计员',
}

// ===== 消息类型（与后端 MessageType 同源，7.6.3） =====
export const MESSAGE_TYPE = {
  TEXT: 'text',
  IMAGE: 'image',
  VOICE: 'voice',
  LOCATION: 'location',
  EVENT: 'event',
} as const

/** 消息类型中文名 */
export const MESSAGE_TYPE_LABELS: Record<string, string> = {
  text: '文本',
  image: '图片',
  voice: '语音',
  location: '位置',
  event: '事件',
}

// ===== 消息角色（与后端 MessageRole 同源） =====
export const MESSAGE_ROLE = {
  /** 用户 */
  USER: 'user',
  /** 助手 */
  ASSISTANT: 'assistant',
  /** 工具 */
  TOOL: 'tool',
} as const

/** 消息角色中文名 */
export const MESSAGE_ROLE_LABELS: Record<string, string> = {
  user: '用户',
  assistant: '助手',
  tool: '工具',
}

// ===== 消息发送状态（对齐 wx_message.send_status COMMENT） =====
export const SEND_STATUS = {
  /** 待发送 */
  PENDING: 0,
  /** 发送成功 */
  SUCCESS: 1,
  /** 发送失败 */
  FAILED: 2,
} as const

/** 消息发送状态中文名 */
export const SEND_STATUS_LABELS: Record<number, string> = {
  0: '待发送',
  1: '发送成功',
  2: '发送失败',
}

// ===== 工具调用状态（对齐 log_tool_call.status COMMENT，与后端 ToolStatus 同源） =====
export const TOOL_STATUS = {
  /** 成功 */
  SUCCESS: 0,
  /** 失败 */
  FAILED: 1,
  /** 降级 */
  DEGRADED: 2,
  /** 超时 */
  TIMEOUT: 3,
  /** 未执行（工具未注册/参数非法） */
  NOT_EXECUTED: 4,
} as const

/** 工具调用状态中文名 */
export const TOOL_STATUS_LABELS: Record<number, string> = {
  0: '成功',
  1: '失败',
  2: '降级',
  3: '超时',
  4: '未执行',
}

// ===== 会话状态（对齐 wx_session.state COMMENT，与后端 SessionState 同源） =====
export const SESSION_STATE = {
  /** 空闲 */
  IDLE: 'IDLE',
  /** 闲聊中 */
  CHATTING: 'CHATTING',
  /** 任务执行中 */
  TASKING: 'TASKING',
  /** 降级 */
  DEGRADED: 'DEGRADED',
} as const

// ===== 分页契约（与后端 PageQuery/PageResult 同源，G-10/G-25） =====
export const PAGE = {
  /** 默认页码 */
  DEFAULT_PAGE: 1,
  /** 默认每页条数 */
  DEFAULT_PAGE_SIZE: 20,
  /** 每页条数上限 */
  MAX_PAGE_SIZE: 100,
} as const

// ===== 错误码镜像（与后端 common/error/ErrorCode 同源，8.5） =====
export const ERROR_CODE = {
  /** 成功 */
  SUCCESS: 0,
  /** 参数缺失 */
  PARAM_MISSING: 10001,
  /** 参数格式非法 */
  PARAM_INVALID: 10002,
  /** 分页参数越界 */
  PAGE_OUT_OF_RANGE: 10003,
  /** 未认证 */
  UNAUTHENTICATED: 20001,
  /** Token 过期 */
  TOKEN_EXPIRED: 20002,
  /** 权限不足 */
  FORBIDDEN: 20003,
  /** 宠物昵称重复 */
  PET_NAME_DUPLICATE: 30001,
  /** 宠物不存在 */
  PET_NOT_FOUND: 30003,
  /** 用户级限流 */
  USER_RATE_LIMITED: 60001,
  /** 系统内部错误 */
  SYSTEM_ERROR: 50003,
} as const
