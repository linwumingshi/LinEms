import { describe, expect, it } from 'vitest'
import {
  buildTrend,
  levelTag,
  levelText,
  statusTag,
  statusText,
  summarizeRecords,
  toLocal,
  tsToLocal,
  typeText,
} from '@/utils/alarmFormat'
import type { AlarmRecord } from '@/types/models'

function record(over: Partial<AlarmRecord>): AlarmRecord {
  return {
    alarmEventId: 'evt-1',
    tenantId: 1,
    deviceId: 100,
    productKey: 'pk',
    ruleId: 1,
    ruleCode: 'ALM_TEMP_HIGH',
    level: 3,
    type: 1,
    status: 0,
    statusName: 'ACTIVE',
    message: '温度过高',
    ext: {},
    triggeredTime: '2026-08-06T10:00:00',
    recoveredTime: null,
    ackedBy: null,
    ackTime: null,
    ...over,
  }
}

/** 生成 n 天前的 LocalDateTime 字符串（本地时区） */
function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T00:00:00`
}

describe('levelText / levelTag', () => {
  it('映射 1-4 级文案', () => {
    expect(levelText(1)).toBe('提示')
    expect(levelText(2)).toBe('一般')
    expect(levelText(3)).toBe('严重')
    expect(levelText(4)).toBe('危急')
  })
  it('未知级别回退', () => {
    expect(levelText(9)).toBe('未知(9)')
    expect(levelTag(9)).toBe('info')
  })
  it('级别→tag 类型映射', () => {
    expect(levelTag(1)).toBe('info')
    expect(levelTag(2)).toBe('primary')
    expect(levelTag(3)).toBe('warning')
    expect(levelTag(4)).toBe('danger')
  })
})

describe('statusText / statusTag / typeText', () => {
  it('状态映射', () => {
    expect(statusText(0)).toBe('触发中')
    expect(statusText(1)).toBe('已恢复')
    expect(statusText(2)).toBe('已确认')
    expect(statusTag(0)).toBe('danger')
    expect(statusTag(1)).toBe('success')
  })
  it('类型映射', () => {
    expect(typeText(1)).toBe('属性')
    expect(typeText(2)).toBe('事件')
    expect(typeText(3)).toBe('策略')
  })
})

describe('toLocal / tsToLocal', () => {
  it('空值回退 -', () => {
    expect(toLocal(null)).toBe('-')
    expect(toLocal('')).toBe('-')
    expect(toLocal(undefined)).toBe('-')
  })
  it('合法时间转本地展示', () => {
    expect(toLocal('2026-08-06T10:15:30')).toContain('10:15:30')
  })
  it('毫秒时间戳展示', () => {
    const d = new Date(2026, 7, 6, 9, 8, 7) // 2026-08-06 09:08:07 本地
    expect(tsToLocal(d.getTime())).toContain('09:08:07')
  })
})

describe('summarizeRecords', () => {
  it('汇总状态与级别计数、去重设备', () => {
    const records = [
      record({ alarmEventId: 'a', deviceId: 100, status: 0, level: 3 }),
      record({ alarmEventId: 'b', deviceId: 100, status: 0, level: 4 }),
      record({ alarmEventId: 'c', deviceId: 200, status: 1, level: 3 }),
      record({ alarmEventId: 'd', deviceId: 200, status: 2, level: 3 }),
    ]
    const s = summarizeRecords(records)
    expect(s.total).toBe(4)
    expect(s.active).toBe(2)
    expect(s.recovered).toBe(1)
    expect(s.acked).toBe(1)
    expect(s.deviceCount).toBe(2)
    expect(s.levelCount[3]).toBe(3)
    expect(s.levelCount[4]).toBe(1)
  })
})

describe('buildTrend', () => {
  it('按天分桶（今日与 n 天前）', () => {
    const records = [
      record({ triggeredTime: daysAgo(0) }),
      record({ triggeredTime: daysAgo(0) }),
      record({ triggeredTime: daysAgo(3) }),
      record({ triggeredTime: '2000-01-01T00:00:00' }), // 窗口外，应丢弃
    ]
    const trend = buildTrend(records, 7)
    expect(trend).toHaveLength(7)
    const total = trend.reduce((acc, t) => acc + t.count, 0)
    expect(total).toBe(3)
    expect(trend[6].count).toBe(2) // 今日桶在末尾
  })
})
