import http from './http'
import type { PropertyHistoryView } from '@/types/models'

export interface TsHistoryParams {
  deviceId: string
  productKey: string
  /** 物模型属性标识，1~10 个；序列化为逗号分隔 */
  identifiers: string[]
  /** epoch 毫秒；缺省近 24h */
  startTime?: number
  endTime?: number
  /** 图表用 asc、表格用 desc，默认 desc */
  order?: 'asc' | 'desc'
  page?: number
  size?: number
}

/** 时序 API（网关 /api/tsdb/** StripPrefix=1 → energy-tsdb） */
export const tsdbApi = {
  /** 属性历史查询（TDengine 宽表） */
  propertyHistory(params: TsHistoryParams): Promise<PropertyHistoryView> {
    const query: Record<string, unknown> = {
      deviceId: params.deviceId,
      productKey: params.productKey,
      identifiers: params.identifiers.join(','),
      order: params.order ?? 'desc',
      page: params.page ?? 1,
      size: params.size ?? 20,
    }
    if (params.startTime !== undefined) query.startTime = params.startTime
    if (params.endTime !== undefined) query.endTime = params.endTime
    return http.get('/api/tsdb/property/history', { params: query })
  },
}
