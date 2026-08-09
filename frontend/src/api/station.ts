import http from './http'
import type { PageResult, Station } from '@/types/models'

/** 电站资产 API（网关路由 /api/station/** → energy-station，StripPrefix=1） */
export const stationApi = {
  /** GET /api/station/page 分页查询（后端 StationQuery 用 pageNum，勿与 EMS 的 pageNo 混淆） */
  stationPage(params: Record<string, unknown>): Promise<PageResult<Station>> {
    return http.get('/api/station/page', { params })
  },
}
