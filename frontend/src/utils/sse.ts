import { STORAGE_KEYS } from '@/utils/constants'
import type { ConsoleConnectionStatus, ConsoleEventVO, ConsoleEventType } from '@/types/console'

/**
 * 控制台 SSE 订阅封装（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * 特性：
 * - 自动重连：断线指数退避（1s → 2s → 4s …，上限 ~10s）；
 * - 续传：重连时携带 Last-Event-ID（从本地记录的最后收到的 id，以 ?lastEventId= 传递，
 *   因原生 EventSource 不支持自定义请求头；浏览器原生重连亦会自动带 Last-Event-ID 头）；
 * - 解析 id / event / data 并统一派发为 ConsoleEventVO。
 */

/** 已知事件名（与后端 ConsoleEventType 对齐），用于 addEventListener 精确分发 */
const KNOWN_TYPES: ConsoleEventType[] = ['TOOL_START', 'TOOL_END', 'MESSAGE_DELTA', 'ERROR', 'DONE']

/** 重连退避上限（ms） */
const MAX_BACKOFF_MS = 10_000

/** 订阅句柄 */
export interface ConsoleSubscription {
  /** 取消订阅并关闭连接 */
  unsubscribe: () => void
}

/**
 * 订阅控制台事件流。
 *
 * @param onEvent  收到事件回调
 * @param onStatus 连接状态变化回调（可选）
 * @returns 订阅句柄
 */
export function subscribeConsole(
  onEvent: (event: ConsoleEventVO) => void,
  onStatus?: (status: ConsoleConnectionStatus) => void,
): ConsoleSubscription {
  const baseUrl = buildConsoleUrl()
  let source: EventSource | null = null
  let lastId = 0
  let attempt = 0
  let closedByUser = false
  let timer: ReturnType<typeof setTimeout> | null = null

  const parse = (data: string): ConsoleEventVO | null => {
    if (!data) {
      return null
    }
    try {
      return JSON.parse(data) as ConsoleEventVO
    } catch {
      return null
    }
  }

  const dispatch = (ev: MessageEvent): void => {
    if (ev.lastEventId) {
      const n = Number(ev.lastEventId)
      if (Number.isFinite(n)) {
        lastId = n
      }
    }
    const vo = parse(ev.data)
    if (vo) {
      onEvent(vo)
    }
  }

  const connect = (): void => {
    if (closedByUser) {
      return
    }
    onStatus?.(attempt === 0 ? 'connecting' : 'reconnecting')
    const sep = baseUrl.includes('?') ? '&' : '?'
    const url = lastId > 0 ? `${baseUrl}${sep}lastEventId=${lastId}` : baseUrl
    const es = new EventSource(url)
    source = es

    es.onopen = () => {
      attempt = 0
      onStatus?.('open')
    }
    es.onmessage = (ev) => dispatch(ev)

    for (const type of KNOWN_TYPES) {
      es.addEventListener(type, (ev) => dispatch(ev as MessageEvent))
    }

    es.onerror = () => {
      es.close()
      if (closedByUser) {
        return
      }
      const delay = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** attempt)
      attempt++
      onStatus?.('reconnecting')
      timer = setTimeout(connect, delay)
    }
  }

  connect()

  return {
    unsubscribe: () => {
      closedByUser = true
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      source?.close()
      onStatus?.('closed')
    },
  }
}

/** 拼接 SSE 地址：基础路径 + /sse/console + ?token=（JWT 取自 localStorage） */
function buildConsoleUrl(): string {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN) ?? ''
  const sep = base.includes('?') ? '&' : '?'
  return token ? `${base}/sse/console${sep}token=${encodeURIComponent(token)}` : `${base}/sse/console`
}
