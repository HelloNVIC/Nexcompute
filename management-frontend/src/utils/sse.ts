import { useAuthStore } from '@/stores/auth'

export type SseEventType =
  | 'monitoring'
  | 'notification'
  | 'ticket'
  | 'container'
  | 'storage-pool'
  | 'instance'
  // platform-env-ota-realtime D8：右下角实时 Toast 事件
  | 'container.changed'
  | 'ticket.changed'
  | 'storage.changed'

type EventHandler = (data: unknown) => void

/**
 * SSE 客户端封装（任务 1.2）
 * 复用同一套 SSE 连接，通过 event 类型区分通道（监控 / 通知）
 * 浏览器原生 EventSource API 自动重连
 */
export class SseClient {
  private eventSource: EventSource | null = null
  private listeners = new Map<SseEventType, Set<EventHandler>>()
  private url: string

  constructor(path: string) {
    const auth = useAuthStore()
    // EventSource 不支持自定义 header，token 走 query
    const base = import.meta.env.VITE_API_BASE ?? '/api'
    const sep = path.includes('?') ? '&' : '?'
    this.url = `${base}${path}${sep}token=${encodeURIComponent(auth.token ?? '')}`
  }

  connect(): void {
    if (this.eventSource) return
    this.eventSource = new EventSource(this.url)

    this.eventSource.onopen = () => {
      console.debug('[SSE] connected')
    }

    this.eventSource.onerror = () => {
      console.warn('[SSE] error, browser will auto-reconnect')
    }

    // 监听各事件类型
    for (const eventType of this.listeners.keys()) {
      this.bindEvent(eventType)
    }
    // 默认 message 通道
    this.eventSource.onmessage = (ev) => {
      this.dispatch('notification', ev.data)
    }
  }

  private bindEvent(eventType: SseEventType): void {
    if (!this.eventSource) return
    this.eventSource.addEventListener(eventType, (ev: MessageEvent) => {
      try {
        const data = JSON.parse(ev.data)
        this.dispatch(eventType, data)
      } catch {
        this.dispatch(eventType, ev.data)
      }
    })
  }

  on(eventType: SseEventType, handler: EventHandler): () => void {
    if (!this.listeners.has(eventType)) {
      this.listeners.set(eventType, new Set())
      if (this.eventSource) this.bindEvent(eventType)
    }
    this.listeners.get(eventType)!.add(handler)
    return () => this.off(eventType, handler)
  }

  off(eventType: SseEventType, handler: EventHandler): void {
    this.listeners.get(eventType)?.delete(handler)
  }

  private dispatch(eventType: SseEventType, data: unknown): void {
    this.listeners.get(eventType)?.forEach((h) => {
      try {
        h(data)
      } catch (e) {
        console.error(`[SSE] handler error for ${eventType}`, e)
      }
    })
  }

  close(): void {
    this.eventSource?.close()
    this.eventSource = null
  }
}

let sharedClient: SseClient | null = null

export function getSseClient(): SseClient {
  if (!sharedClient) {
    sharedClient = new SseClient('/sse/subscribe')
    sharedClient.connect()
  }
  return sharedClient
}

export function closeSseClient(): void {
  sharedClient?.close()
  sharedClient = null
}
