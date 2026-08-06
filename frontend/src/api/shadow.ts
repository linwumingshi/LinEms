import http from './http'
import type { DesiredResult, ShadowView } from '@/types/models'

export interface AlarmRecordsParams {
  tenantId?: number
  ruleId?: number
  deviceId?: number
  level?: number
  status?: number
  startTime?: string
  endTime?: string
  page: number
  size: number
}

/** 影子 API（网关路由 /api/shadow/** → energy-shadow） */
export const shadowApi = {
  /** GET /api/shadow/{deviceId} 影子合并视图 */
  getShadow(deviceId: number): Promise<ShadowView> {
    return http.get(`/api/shadow/${deviceId}`)
  },

  /** PUT /api/shadow/{deviceId}/desired 设置期望值 → 返回 delta */
  setDesired(deviceId: number, desired: Record<string, unknown>): Promise<DesiredResult> {
    return http.put(`/api/shadow/${deviceId}/desired`, { desired })
  },
}
