import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { LIVE_EVENTS_LIMIT, useAlarmStore } from '@/stores/alarm'
import type { AlarmPush } from '@/types/models'
import type { SocketLike } from '@/ws/alarmSocket'

/** 可控的 SocketLike 替身：可主动 emit 消息与连接状态 */
function fakeSocket(): SocketLike & {
  emit: (msg: AlarmPush) => void
  emitStatus: (v: boolean) => void
  connectCalls: () => number
} {
  const handlers = new Set<(msg: AlarmPush) => void>()
  const statusHandlers = new Set<(v: boolean) => void>()
  let connectCount = 0
  return {
    connect: () => {
      connectCount += 1
    },
    close: () => {
      handlers.clear()
      statusHandlers.clear()
    },
    subscribe(handler) {
      handlers.add(handler)
      return () => handlers.delete(handler)
    },
    onStatusChange(handler) {
      statusHandlers.add(handler)
    },
    emit(msg) {
      handlers.forEach((h) => h(msg))
    },
    emitStatus(v) {
      statusHandlers.forEach((h) => h(v))
    },
    connectCalls: () => connectCount,
  }
}

let seq = 0
function push(over: Partial<AlarmPush> = {}): AlarmPush {
  seq += 1
  return {
    alarmEventId: `evt-${seq}`,
    tenantId: '1',
    deviceId: '100',
    productKey: 'pk',
    ruleId: '1',
    ruleCode: 'ALM_TEMP_HIGH',
    level: 3,
    type: 1,
    status: 'ACTIVE',
    message: '温度过高',
    ext: {},
    ts: Date.now(),
    ...over,
  }
}

describe('useAlarmStore', () => {
  beforeEach(() => {
    seq = 0
    setActivePinia(createPinia())
  })

  it('ACTIVE 事件：前插实时列表 + 未读数自增', () => {
    const store = useAlarmStore()
    store.handlePush(push())
    store.handlePush(push())
    expect(store.liveEvents).toHaveLength(2)
    expect(store.liveEvents[0].alarmEventId).toBe('evt-2') // 最新在前
    expect(store.unread).toBe(2)
  })

  it('RECOVERED 事件：前插列表但不计入未读', () => {
    const store = useAlarmStore()
    store.handlePush(push({ status: 'ACTIVE' }))
    store.handlePush(push({ status: 'RECOVERED' }))
    expect(store.unread).toBe(1)
    expect(store.activeLiveEvents).toHaveLength(1)
  })

  it('consume 移除指定事件', () => {
    const store = useAlarmStore()
    store.handlePush(push({ alarmEventId: 'a' }))
    store.handlePush(push({ alarmEventId: 'b' }))
    store.consume('a')
    expect(store.liveEvents.some((e) => e.alarmEventId === 'a')).toBe(false)
    expect(store.liveEvents.some((e) => e.alarmEventId === 'b')).toBe(true)
  })

  it('超过上限截断最旧事件', () => {
    const store = useAlarmStore()
    // seq 从 1..110；保留最近 100 条 → evt-11..evt-110
    for (let i = 0; i < LIVE_EVENTS_LIMIT + 10; i++) {
      store.handlePush(push())
    }
    expect(store.liveEvents).toHaveLength(LIVE_EVENTS_LIMIT)
    expect(store.liveEvents[0].alarmEventId).toBe('evt-110')
    expect(store.liveEvents[LIVE_EVENTS_LIMIT - 1].alarmEventId).toBe('evt-11')
  })

  it('clearUnread 清零', () => {
    const store = useAlarmStore()
    store.handlePush(push())
    store.clearUnread()
    expect(store.unread).toBe(0)
  })

  it('initSocket：注入替身并订阅；推送/连接状态回写 store', () => {
    const store = useAlarmStore()
    const sock = fakeSocket()
    store.initSocket(sock)
    expect(sock.connectCalls()).toBe(1)

    sock.emitStatus(true)
    expect(store.connected).toBe(true)
    sock.emitStatus(false)
    expect(store.connected).toBe(false)

    sock.emit(push({ alarmEventId: 'live-1' }))
    expect(store.liveEvents[0].alarmEventId).toBe('live-1')
    expect(store.unread).toBe(1)
  })

  it('initSocket 幂等：重复调用不重复连接', () => {
    const store = useAlarmStore()
    const sock = fakeSocket()
    store.initSocket(sock)
    store.initSocket(sock)
    expect(sock.connectCalls()).toBe(1)
  })

  it('initSocket 缺省使用真实 AlarmSocket（不抛错即通过）', () => {
    const store = useAlarmStore()
    expect(() => store.initSocket()).not.toThrow()
    expect(store.connected).toBe(false)
  })
})
