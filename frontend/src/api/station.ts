import http from './http'
import type { PageResult, Station, StationSaveReq } from '@/types/models'

/** 电站资产 API（网关路由 /api/station/** → energy-station，StripPrefix=1） */
export const stationApi = {
  /** GET /api/station/page 分页查询（后端 StationQuery 用 pageNum，勿与 EMS 的 pageNo 混淆） */
  stationPage(params: Record<string, unknown>): Promise<PageResult<Station>> {
    return http.get('/api/station/page', { params })
  },
  create(body: StationSaveReq): Promise<string> {
    return http.post('/api/station', body)
  },
  detail(stationId: string): Promise<Station> {
    return http.get(`/api/station/${stationId}`)
  },
  update(stationId: string, body: StationSaveReq): Promise<void> {
    return http.put(`/api/station/${stationId}`, body)
  },
  remove(stationId: string): Promise<void> {
    return http.delete(`/api/station/${stationId}`)
  },
}
