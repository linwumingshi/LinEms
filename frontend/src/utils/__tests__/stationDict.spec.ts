import { beforeEach, describe, expect, it, vi } from 'vitest'
import { stationApi } from '@/api/station'
import { loadStations, stationName, _resetStationCache } from '@/utils/stationDict'

vi.mock('@/api/station', () => ({
  stationApi: { stationPage: vi.fn() },
}))

const mockStationPage = vi.mocked(stationApi.stationPage)

const stations = [
  { stationId: '1', stationName: '深圳一号站', status: 1 },
  { stationId: '2', stationName: '东莞储能站', status: 1 },
]

const pageResult = { records: stations, total: 2, pages: 1, current: 1, size: 100 }

beforeEach(() => {
  _resetStationCache()
  mockStationPage.mockReset()
  mockStationPage.mockResolvedValue(pageResult as never)
})

describe('stationDict', () => {
  it('loadStations 首次拉取后二次调用不重复请求（缓存）', async () => {
    const first = await loadStations()
    expect(mockStationPage).toHaveBeenCalledTimes(1)
    expect(mockStationPage).toHaveBeenCalledWith({ pageNum: 1, pageSize: 100 })
    const second = await loadStations()
    expect(mockStationPage).toHaveBeenCalledTimes(1)
    expect(second).toBe(first)
  })

  it('loadStations(force=true) 强制重拉', async () => {
    await loadStations()
    await loadStations(true)
    expect(mockStationPage).toHaveBeenCalledTimes(2)
  })

  it('_resetStationCache 后重新拉取', async () => {
    await loadStations()
    _resetStationCache()
    await loadStations()
    expect(mockStationPage).toHaveBeenCalledTimes(2)
  })

  it('stationName：已知回名称 / 未知回原 id / 空回空串', () => {
    expect(stationName('1', stations)).toBe('深圳一号站')
    expect(stationName(2, stations)).toBe('东莞储能站')
    expect(stationName('999', stations)).toBe('999')
    expect(stationName(undefined, stations)).toBe('')
    expect(stationName(null, stations)).toBe('')
  })
})
