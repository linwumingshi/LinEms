import { describe, expect, it } from 'vitest'
import { mergeCurves } from '@/utils/planCurve'

describe('mergeCurves', () => {
  it('空数组 → 空曲线', () => {
    expect(mergeCurves([])).toEqual({ times: [], power: [] })
  })

  it('单台 → 原样返回', () => {
    const c = { times: ['02:00', '02:05'], power: [100, 80] }
    expect(mergeCurves([c])).toEqual(c)
  })

  it('多台同刻求和，时间并集升序', () => {
    const merged = mergeCurves([
      { times: ['02:00', '02:05', '02:10'], power: [100, 80, 60] },
      { times: ['02:00', '02:05'], power: [50, 20] },
    ])
    expect(merged).toEqual({ times: ['02:00', '02:05', '02:10'], power: [150, 100, 60] })
  })

  it('单侧缺时刻按 0 求和', () => {
    const merged = mergeCurves([
      { times: ['03:00'], power: [120] },
      { times: ['03:00', '03:05'], power: [30, 40] },
    ])
    expect(merged).toEqual({ times: ['03:00', '03:05'], power: [150, 40] })
  })
})
