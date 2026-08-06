import http from './http'
import type { AlarmRecord, AlarmRule, PageResult } from '@/types/models'

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

/** 告警 API（网关路由 /api/alarm/** → energy-alarm） */
export const alarmApi = {
  /** GET /api/alarm/records 告警记录分页查询 */
  records(params: AlarmRecordsParams): Promise<PageResult<AlarmRecord>> {
    return http.get('/api/alarm/records', { params })
  },

  /** POST /api/alarm/ack/{alarmEventId} 人工确认（幂等） */
  ack(alarmEventId: string, ackedBy: string): Promise<void> {
    return http.post(`/api/alarm/ack/${alarmEventId}`, { ackedBy })
  },

  /** GET /api/alarm/rules 启用规则列表 */
  rules(tenantId?: number): Promise<AlarmRule[]> {
    return http.get('/api/alarm/rules', { params: tenantId ? { tenantId } : {} })
  },
}
