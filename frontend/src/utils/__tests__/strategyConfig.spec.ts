import { describe, expect, it } from 'vitest'
import {
  parseJsonConfig,
  parsePeakValleyConfig,
  serializePeakValley,
  validatePeakValleyConfig,
  validatePeakValleySaveable,
} from '@/utils/strategyConfig'

describe('strategyConfig', () => {
  const validConfig = `{
    "chargeWindows": [{"start": "02:00", "end": "06:00", "powerLimit": 100}],
    "dischargeWindows": [{"start": "18:00", "end": "22:00", "powerLimit": 80}],
    "socRange": {"min": 10, "max": 90}
  }`

  it('parseJsonConfig：合法 JSON', () => {
    expect(parseJsonConfig('{"a":1}').ok).toBe(true)
    expect(parseJsonConfig('"str"').ok).toBe(true)
  })

  it('parseJsonConfig：非法 JSON / 空串', () => {
    const bad = parseJsonConfig('{bad')
    expect(bad.ok).toBe(false)
    if (!bad.ok) expect(bad.error).toMatch(/配置不是合法 JSON/)
    expect(parseJsonConfig('').ok).toBe(false)
  })

  it('parsePeakValleyConfig：合法 → config + rest 保留未知键', () => {
    const r = parsePeakValleyConfig(JSON.parse(validConfig))
    expect(r.ok).toBe(true)
    if (r.ok) {
      expect(r.config.chargeWindows).toHaveLength(1)
      expect(r.config.chargeWindows[0]).toEqual({ start: '02:00', end: '06:00', powerLimit: 100 })
      expect(r.config.dischargeWindows).toHaveLength(1)
      expect(r.rest).toEqual({ socRange: { min: 10, max: 90 } })
    }
  })

  it('parsePeakValleyConfig：顶层非对象 / 缺数组', () => {
    expect(parsePeakValleyConfig('x').ok).toBe(false)
    expect(parsePeakValleyConfig([1, 2]).ok).toBe(false)
    expect(parsePeakValleyConfig({ chargeWindows: [] }).ok).toBe(false)
    expect(parsePeakValleyConfig({ chargeWindows: {}, dischargeWindows: [] }).ok).toBe(false)
  })

  it('parsePeakValleyConfig：priceDriven=true 缺窗口数组 → 视作空数组', () => {
    const r = parsePeakValleyConfig({ priceDriven: true, chargePower: 80 })
    expect(r.ok).toBe(true)
    if (r.ok) {
      expect(r.config.chargeWindows).toHaveLength(0)
      expect(r.config.dischargeWindows).toHaveLength(0)
      expect(r.rest).toEqual({ priceDriven: true, chargePower: 80 })
    }
  })

  it('parsePeakValleyConfig：坏时间格式', () => {
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '25:00', end: '06:00', powerLimit: 1 }], dischargeWindows: [] }).ok).toBe(false)
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '02:00', end: '06:60', powerLimit: 1 }], dischargeWindows: [] }).ok).toBe(false)
  })

  it('parsePeakValleyConfig：start >= end', () => {
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '06:00', end: '06:00', powerLimit: 1 }], dischargeWindows: [] }).ok).toBe(false)
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '08:00', end: '06:00', powerLimit: 1 }], dischargeWindows: [] }).ok).toBe(false)
  })

  it('parsePeakValleyConfig：powerLimit 缺失/0/负数', () => {
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '02:00', end: '06:00' }], dischargeWindows: [] }).ok).toBe(false)
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: 0 }], dischargeWindows: [] }).ok).toBe(false)
    expect(parsePeakValleyConfig({ chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: -5 }], dischargeWindows: [] }).ok).toBe(false)
  })

  it('parsePeakValleyConfig：空窗口数组合法（ID2 可往返结构化编辑）', () => {
    const r = parsePeakValleyConfig({ chargeWindows: [], dischargeWindows: [] })
    expect(r.ok).toBe(true)
    if (r.ok) {
      expect(r.config.chargeWindows).toHaveLength(0)
      expect(r.config.dischargeWindows).toHaveLength(0)
    }
  })

  it('validatePeakValleyConfig：合法 → []', () => {
    expect(validatePeakValleyConfig(validConfig)).toEqual([])
  })

  it('validatePeakValleyConfig：语法错与结构化错各自返回首条', () => {
    expect(validatePeakValleyConfig('{bad')).toHaveLength(1)
    expect(validatePeakValleyConfig(JSON.stringify({
      chargeWindows: [{ start: '08:00', end: '06:00', powerLimit: 1 }],
      dischargeWindows: [],
    }))[0]).toMatch(/结束时间必须晚于开始时间/)
    expect(validatePeakValleyConfig('{"chargeWindows":[],"dischargeWindows":[]}')).toEqual([])
  })

  it('serializePeakValley：rest 合并 + socRange 往返不丢 + 键序稳定', () => {
    const parsed = parsePeakValleyConfig(JSON.parse(validConfig))
    if (!parsed.ok) throw new Error('前置解析失败')
    const s = serializePeakValley(parsed.config, parsed.rest)
    const back = JSON.parse(s)
    expect(back.socRange).toEqual({ min: 10, max: 90 })
    expect(back.chargeWindows[0].powerLimit).toBe(100)
    expect(Object.keys(back)).toEqual(['socRange', 'chargeWindows', 'dischargeWindows'])
  })

  it('validatePeakValleySaveable：空配置 / 空窗口 → 至少一个窗口；合法 → []', () => {
    expect(validatePeakValleySaveable('')).toEqual(['请至少配置一个充电或放电窗口'])
    expect(validatePeakValleySaveable('  ')).toEqual(['请至少配置一个充电或放电窗口'])
    expect(validatePeakValleySaveable('{"chargeWindows":[],"dischargeWindows":[]}')).toEqual(['请至少配置一个充电或放电窗口'])
    expect(validatePeakValleySaveable(validConfig)).toEqual([])
  })

  it('空窗口配置序列化后往返解析 ok（ID2 模式切换）', () => {
    const s = serializePeakValley({ chargeWindows: [], dischargeWindows: [] }, {})
    const back = parsePeakValleyConfig(JSON.parse(s))
    expect(back.ok).toBe(true)
    if (back.ok) expect(back.config.chargeWindows).toHaveLength(0)
  })

  it('validatePeakValleySaveable：priceDriven=true 无窗口 → 通过', () => {
    expect(validatePeakValleySaveable('{"priceDriven":true,"chargePower":80}')).toEqual([])
    expect(validatePeakValleySaveable('{"priceDriven":true}')).toEqual([])
  })

  it('validatePeakValleySaveable：priceDriven=false/缺失 无窗口 → 仍拦截', () => {
    expect(validatePeakValleySaveable('{"priceDriven":false,"chargeWindows":[],"dischargeWindows":[]}')).toEqual(['请至少配置一个充电或放电窗口'])
  })

  it('serializePeakValley：rest 含 priceDriven 键原样保留', () => {
    const s = serializePeakValley({ chargeWindows: [], dischargeWindows: [] }, { priceDriven: true, chargePower: 80 })
    const back = JSON.parse(s)
    expect(back.priceDriven).toBe(true)
    expect(back.chargePower).toBe(80)
  })
})
