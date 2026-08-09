import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from '@/api/http'
import { tsdbApi } from '@/api/tsdb'

vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const mockedGet = vi.mocked(http.get)

describe('tsdbApi.propertyHistory', () => {
  beforeEach(() => { mockedGet.mockReset() })

  it('identifiers 数组 join 为逗号分隔', async () => {
    mockedGet.mockResolvedValue({ deviceId: 'd', productKey: 'pk', total: 0, records: [] })
    await tsdbApi.propertyHistory({ deviceId: '8000000000000000001', productKey: 'snd_ess_pcs', identifiers: ['soc', 'voltage'] })
    expect(mockedGet).toHaveBeenCalledWith('/api/tsdb/property/history', {
      params: expect.objectContaining({ identifiers: 'soc,voltage' }),
    })
  })

  it('缺省 order=desc page=1 size=20；可选时间缺省不带', async () => {
    mockedGet.mockResolvedValue({ deviceId: 'd', productKey: 'pk', total: 0, records: [] })
    await tsdbApi.propertyHistory({ deviceId: 'd', productKey: 'pk', identifiers: ['temp'] })
    expect(mockedGet).toHaveBeenCalledWith('/api/tsdb/property/history', {
      params: expect.objectContaining({ order: 'desc', page: 1, size: 20 }),
    })
    const params = mockedGet.mock.calls[0][1]!.params as Record<string, unknown>
    expect(params.startTime).toBeUndefined()
  })

  it('显式 startTime/endTime 透传为数字', async () => {
    mockedGet.mockResolvedValue({ deviceId: 'd', productKey: 'pk', total: 0, records: [] })
    await tsdbApi.propertyHistory({ deviceId: 'd', productKey: 'pk', identifiers: ['soc'], startTime: 1000, endTime: 2000 })
    const params = mockedGet.mock.calls[0][1]!.params as Record<string, unknown>
    expect(params.startTime).toBe(1000)
    expect(params.endTime).toBe(2000)
  })
})
