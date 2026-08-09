import { describe, expect, it, vi } from 'vitest'
import { emsApi } from '@/api/ems'
import { ensureConstraint, hasFullConstraint, constraintReady } from '@/utils/planGate'
import type { EmsConstraint } from '@/types/models'

vi.mock('@/api/ems', () => ({ emsApi: { constraintGet: vi.fn() } }))
const mockGet = vi.mocked(emsApi.constraintGet)

const full = {
  constraintId: 'c1', tenantId: 't1', stationId: '1', status: 1,
  socMin: 10, socMax: 90, chargePowerMax: 200, dischargePowerMax: 200,
  tempMax: 60, voltageMax: null, currentMax: null, safetyEnvelope: null,
  createTime: '', updateTime: '',
} as unknown as EmsConstraint

describe('planGate', () => {
  it('hasFullConstraint：null/undefined → false', () => {
    expect(hasFullConstraint(null)).toBe(false)
    expect(hasFullConstraint(undefined)).toBe(false)
  })

  it('hasFullConstraint：4 字段任一 null → false', () => {
    expect(hasFullConstraint({ ...full, socMin: null })).toBe(false)
    expect(hasFullConstraint({ ...full, socMax: null })).toBe(false)
    expect(hasFullConstraint({ ...full, chargePowerMax: null })).toBe(false)
    expect(hasFullConstraint({ ...full, dischargePowerMax: null })).toBe(false)
  })

  it('hasFullConstraint：4 字段全非空 → true', () => {
    expect(hasFullConstraint(full)).toBe(true)
  })

  it('ensureConstraint：成功 → 行；失败 → null', async () => {
    mockGet.mockResolvedValue(full)
    await expect(ensureConstraint('1')).resolves.toBe(full)
    mockGet.mockRejectedValue(new Error('boom'))
    await expect(ensureConstraint('1')).resolves.toBeNull()
  })

  it('constraintReady：齐全 → true；失败/缺行 → false', async () => {
    mockGet.mockResolvedValue(full)
    await expect(constraintReady('1')).resolves.toBe(true)
    mockGet.mockRejectedValue(new Error('boom'))
    await expect(constraintReady('1')).resolves.toBe(false)
  })
})
