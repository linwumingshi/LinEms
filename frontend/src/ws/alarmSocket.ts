import type { AlarmPush } from '@/types/models'
import { getToken } from '@/utils/auth-storage'

/**
 * 最小化连接抽象：真实实现与测试替身共用（store 依赖该接口而非具体类）。
 */
export interface SocketLike {
  connect(): void
  subscribe(handler: (msg: AlarmPush) => void): () => void
  onStatusChange(handler: (connected: boolean) => void): void
  close(): void
}

/**
 * /ws/alarm 实时告警 WebSocket 客户端。
 *
 * <ul>
 *   <li>开发环境经 Vite 代理 → 网关 8000 /ws/** → energy-alarm WebSocketHandler；</li>
 *   <li>浏览器原生 WS 无法携带请求头，token 经 URL 查询参数传递（P0-2），
 *       握手前从 auth-storage 读取，未登录则不带参数（服务端 401 拒绝）；</li>
 *   <li>指数退避自动重连（1s→8 次封顶），心跳 25s 探测保活；</li>
 *   <li>认证拒绝判定：连接从未 onopen 即关闭（close code 1006）→ 停止自动重连，
 *       避免带失效 token 无限空转（登出/换号后重新登录会重建连接）；</li>
 *   <li>收到消息做结构校验（status ∈ ACTIVE/RECOVERED），非法帧丢弃并告警日志。</li>
 * </ul>
 */
export class AlarmSocket implements SocketLike {
  private readonly url: string
  private readonly maxRetry = 8
  private ws: WebSocket | null = null
  private messageHandlers = new Set<(msg: AlarmPush) => void>()
  private statusHandlers = new Set<(connected: boolean) => void>()
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private manualClosed = false
  private authRejected = false
  private everOpened = false
  private retryCount = 0

  constructor(basePath = '/ws/alarm') {
    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://'
    this.url = `${scheme}${location.host}${basePath}`
  }

  /** 拼 URL：附 ?token=<jwt>（浏览器原生 WS 无法设置 Authorization 头，P0-2） */
  private urlWithToken(): string {
    const token = getToken()
    if (!token) return this.url
    const sep = this.url.includes('?') ? '&' : '?'
    return `${this.url}${sep}token=${encodeURIComponent(token)}`
  }

  connect(): void {
    this.manualClosed = false
    try {
      this.ws = new WebSocket(this.urlWithToken())
    } catch (e) {
      console.error('[AlarmSocket] 创建 WebSocket 失败', e)
      this.scheduleReconnect()
      return
    }
    this.ws.onopen = () => {
      this.everOpened = true
      this.retryCount = 0
      this.notifyStatus(true)
      this.startHeartbeat()
    }
    this.ws.onmessage = (event: MessageEvent) => this.handleMessage(event)
    this.ws.onclose = () => {
      this.stopHeartbeat()
      this.notifyStatus(false)
      this.ws = null
      // 从未 onopen 即被关闭 → 握手被服务端拒绝（token 缺失/失效/过期）。
      // 继续重连只会带着失效 token 死循环，停止并提示重新登录。
      if (!this.everOpened) {
        this.authRejected = true
        console.warn('[AlarmSocket] WS 握手被拒绝（未认证或 Token 失效），停止自动重连，请重新登录')
        return
      }
      this.scheduleReconnect()
    }
    this.ws.onerror = () => {
      // onclose 随后触发，统一走重连/拒绝逻辑；此处仅留日志
      console.warn('[AlarmSocket] WebSocket 错误，等待重连')
    }
  }

  subscribe(handler: (msg: AlarmPush) => void): () => void {
    this.messageHandlers.add(handler)
    return () => this.messageHandlers.delete(handler)
  }

  onStatusChange(handler: (connected: boolean) => void): void {
    this.statusHandlers.add(handler)
  }

  close(): void {
    this.manualClosed = true
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.stopHeartbeat()
    this.ws?.close()
    this.ws = null
    this.messageHandlers.clear()
    this.statusHandlers.clear()
  }

  private handleMessage(event: MessageEvent): void {
    let msg: unknown
    try {
      msg = JSON.parse(event.data as string)
    } catch {
      console.warn('[AlarmSocket] 忽略非法 JSON 帧')
      return
    }
    if (!this.isAlarmPush(msg)) {
      console.warn('[AlarmSocket] 忽略结构异常帧', msg)
      return
    }
    this.messageHandlers.forEach((handler) => handler(msg as AlarmPush))
  }

  /** 结构校验：必须含 alarmEventId，且 status 取值为 ACTIVE / RECOVERED */
  private isAlarmPush(value: unknown): value is AlarmPush {
    if (!value || typeof value !== 'object') return false
    const v = value as Record<string, unknown>
    return (
      typeof v.alarmEventId === 'string' &&
      (v.status === 'ACTIVE' || v.status === 'RECOVERED')
    )
  }

  private scheduleReconnect(): void {
    if (this.manualClosed || this.authRejected || this.reconnectTimer !== null) return
    if (this.retryCount >= this.maxRetry) {
      console.warn('[AlarmSocket] 重连次数达上限，停止自动重连（可手动刷新页面重试）')
      return
    }
    const delay = Math.min(1000 * 2 ** this.retryCount, 30000)
    this.retryCount += 1
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private startHeartbeat(): void {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send('ping')
      }
    }, 25000)
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      window.clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private notifyStatus(connected: boolean): void {
    this.statusHandlers.forEach((handler) => handler(connected))
  }
}
