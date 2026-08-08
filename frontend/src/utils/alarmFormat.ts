import type { AlarmRecord } from '@/types/models'

/** 告警级别 → 展示文案 */
export function levelText(level: number): string {
  switch (level) {
    case 1:
      return '提示'
    case 2:
      return '一般'
    case 3:
      return '严重'
    case 4:
      return '危急'
    default:
      return `未知(${level})`
  }
}

/** 告警级别 → Element Plus tag 类型 */
export function levelTag(level: number): 'info' | 'primary' | 'warning' | 'danger' {
  switch (level) {
    case 1:
      return 'info'
    case 2:
      return 'primary'
    case 3:
      return 'warning'
    case 4:
      return 'danger'
    default:
      return 'info'
  }
}

/** 记录状态（0 触发中 1 已恢复 2 已确认） → 展示文案 */
export function statusText(status: number): string {
  switch (status) {
    case 0:
      return '触发中'
    case 1:
      return '已恢复'
    case 2:
      return '已确认'
    default:
      return `未知(${status})`
  }
}

/** 记录状态 → tag 类型 */
export function statusTag(status: number): 'danger' | 'success' | 'info' {
  switch (status) {
    case 0:
      return 'danger'
    case 1:
      return 'success'
    default:
      return 'info'
  }
}

/** 触发类型（1 属性 2 事件 3 策略）→ 文案 */
export function typeText(type: number): string {
  switch (type) {
    case 1:
      return '属性'
    case 2:
      return '事件'
    case 3:
      return '策略'
    default:
      return `未知(${type})`
  }
}

/** 后端 LocalDateTime（yyyy-MM-ddTHH:mm:ss）→ 浏览器本地时间展示 */
export function toLocal(dt: string | null | undefined): string {
  if (!dt) return '-'
  const normalized = dt.length === 19 ? `${dt}` : dt
  // Java 序列化输出 2026-08-06T10:15:30，无时区；按本机时区解析展示
  const parsed = new Date(normalized.replace('T', ' ').replace(/-/g, '/'))
  if (Number.isNaN(parsed.getTime())) return dt
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
}

/** 毫秒时间戳 → 展示文案 */
export function tsToLocal(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// ---------------- 驾驶舱聚合（纯函数，便于单测） ----------------

export interface AlarmSummary {
  total: number
  active: number
  recovered: number
  acked: number
  deviceCount: number
  levelCount: Record<number, number>
  /** 近 7 日触发趋势（[day][count]，最新在末尾） */
  trend: Array<{ day: string; count: number }>
}

/** 由告警记录样本聚合出驾驶舱统计（样本窗口内统计，标题需注明口径） */
export function summarizeRecords(records: AlarmRecord[], days = 7): AlarmSummary {
  const levelCount: Record<number, number> = {}
  const deviceIds = new Set<string>()
  let active = 0
  let recovered = 0
  let acked = 0

  for (const r of records) {
    levelCount[r.level] = (levelCount[r.level] ?? 0) + 1
    deviceIds.add(r.deviceId)
    if (r.status === 0) active += 1
    else if (r.status === 1) recovered += 1
    else if (r.status === 2) acked += 1
  }

  const trend = buildTrend(records, days)

  return {
    total: records.length,
    active,
    recovered,
    acked,
    deviceCount: deviceIds.size,
    levelCount,
    trend,
  }
}

/** 按 triggeredTime 聚合近 days 天（含今天）的触发趋势 */
export function buildTrend(records: AlarmRecord[], days = 7): Array<{ day: string; count: number }> {
  const buckets: Array<{ day: string; count: number }> = []
  const now = new Date()

  // 建立 [今天-days+1, 今天] 的日期桶
  const bucketIndex = new Map<string, number>()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)
    const key = `${d.getMonth() + 1}-${d.getDate()}`
    bucketIndex.set(key, buckets.length)
    buckets.push({ day: key, count: 0 })
  }

  for (const r of records) {
    if (!r.triggeredTime) continue
    const d = new Date(r.triggeredTime.replace('T', ' ').replace(/-/g, '/'))
    if (Number.isNaN(d.getTime())) continue
    const key = `${d.getMonth() + 1}-${d.getDate()}`
    const idx = bucketIndex.get(key)
    if (idx !== undefined) buckets[idx].count += 1
  }
  return buckets
}
