import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AlarmPush } from '@/types/models'
import { AlarmSocket, type SocketLike } from '@/ws/alarmSocket'

/** 内存内保留的实时事件上限（超出丢弃最旧，防长时间运行内存膨胀） */
export const LIVE_EVENTS_LIMIT = 100

/**
 * 告警全局状态：/ws/alarm 实时事件流 + 未读数 + 连接状态。
 *
 * <p>设计说明：实时事件只驻留内存（驱动驾驶舱弹窗/角标），权威数据始终来自
 * /api/alarm/records 分页查询；ACK 走 REST，收到 ACK 后从实时列表移除并刷新表格。</p>
 */
export const useAlarmStore = defineStore('alarm', () => {
  /** 实时事件（最新在前） */
  const liveEvents = ref<AlarmPush[]>([])
  /** 触发类未读数（ACTIVE 事件计数，恢复事件清零由页面消费时触发） */
  const unread = ref(0)
  /** 与 /ws/alarm 的连接状态 */
  const connected = ref(false)
  /** 实时列表内仍处于触发中的事件 */
  const activeLiveEvents = computed(() => liveEvents.value.filter((e) => e.status === 'ACTIVE'))

  let socket: SocketLike | null = null

  /** 将一条推送并入实时列表（store 的核心 reducer，暴露供单测直测） */
  function handlePush(msg: AlarmPush): void {
    liveEvents.value.unshift(msg)
    if (liveEvents.value.length > LIVE_EVENTS_LIMIT) {
      liveEvents.value = liveEvents.value.slice(0, LIVE_EVENTS_LIMIT)
    }
    if (msg.status === 'ACTIVE') {
      unread.value += 1
    }
  }

  /**
   * 初始化连接。可注入 SocketLike 测试替身（默认真实 AlarmSocket）。
   * 幂等：已连接时不重复创建。
   */
  function initSocket(customSocket?: SocketLike): void {
    if (socket) return
    socket = customSocket ?? new AlarmSocket()
    socket.subscribe((msg) => handlePush(msg))
    socket.onStatusChange((open) => {
      connected.value = open
    })
    socket.connect()
  }

  function clearUnread(): void {
    unread.value = 0
  }

  /** 消费一条实时事件（页面已处理/已确认后移除） */
  function consume(eventId: string): void {
    liveEvents.value = liveEvents.value.filter((e) => e.alarmEventId !== eventId)
  }

  /** 关闭连接（登出时调用，幂等；置空后下次 initSocket 可重新连接） */
  function closeSocket(): void {
    if (socket) {
      socket.close()
      socket = null
    }
    connected.value = false
  }

  return {
    liveEvents,
    unread,
    connected,
    activeLiveEvents,
    handlePush,
    initSocket,
    clearUnread,
    consume,
    closeSocket,
  }
})
