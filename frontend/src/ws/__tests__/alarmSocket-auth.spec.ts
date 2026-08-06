import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AlarmSocket } from '@/ws/alarmSocket'
import { clearAuth, setToken } from '@/utils/auth-storage'

/** 可控 WebSocket 桩：记录 URL，可手动触发 onopen / onclose */
class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static OPEN = 1
  url: string
  readyState = 0
  onopen: (() => void) | null = null
  onclose: ((ev: CloseEvent) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(): void {}

  close(): void {}

  static emitOpen(): void {
    FakeWebSocket.instances.forEach((w) => {
      w.readyState = FakeWebSocket.OPEN
      w.onopen?.()
    })
  }

  static emitCloseAll(): void {
    FakeWebSocket.instances.forEach((w) => {
      w.onclose?.({ code: 1006 } as CloseEvent)
    })
  }
}

describe('AlarmSocket 认证接线（P0-2）', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('有 token：连接 URL 携带 ?token=<jwt>', () => {
    setToken('jwt-x')
    const sock = new AlarmSocket()
    sock.connect()

    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(FakeWebSocket.instances[0].url).toContain('token=jwt-x')
  })

  it('无 token：URL 不带 token 参数', () => {
    clearAuth()
    const sock = new AlarmSocket()
    sock.connect()

    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(FakeWebSocket.instances[0].url).not.toContain('token=')
  })

  it('认证拒绝（从未 onopen 即 close）→ 停止自动重连', () => {
    vi.useFakeTimers()
    setToken('jwt-stale')
    const sock = new AlarmSocket()
    sock.connect()

    FakeWebSocket.emitCloseAll() // 握手被服务端 401 拒绝
    vi.advanceTimersByTime(60000) // 即使时间流逝也不再发起连接

    expect(FakeWebSocket.instances).toHaveLength(1)
  })

  it('曾成功连接后断开 → 自动重连（新建连接）', () => {
    vi.useFakeTimers()
    setToken('jwt-x')
    const sock = new AlarmSocket()
    sock.connect()

    FakeWebSocket.emitOpen()
    FakeWebSocket.emitCloseAll()
    vi.advanceTimersByTime(1000)

    expect(FakeWebSocket.instances).toHaveLength(2)
  })
})
