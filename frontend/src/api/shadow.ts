import http from './http'
import type { DesiredResult, ShadowView } from '@/types/models'

export interface AlarmRecordsParams {
  tenantId?: string
  ruleId?: string
  deviceId?: string
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
  getShadow(deviceId: string): Promise<ShadowView> {
    return http.get(`/api/shadow/${deviceId}`)
  },

  /** PUT /api/shadow/{deviceId}/desired 设置期望值 → 返回 delta */
  setDesired(deviceId: string, desired: Record<string, unknown>): Promise<DesiredResult> {
    return http.put(`/api/shadow/${deviceId}/desired`, { desired })
  },
}
