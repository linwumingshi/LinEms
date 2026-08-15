import http from './http'
import type { AlarmRecord, AlarmRule, AlarmRuleSaveReq, PageResult } from '@/types/models'

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
  rules(tenantId?: string): Promise<AlarmRule[]> {
    return http.get('/api/alarm/rules', { params: tenantId ? { tenantId } : {} })
  },

  /** POST /api/alarm/rule 新增告警规则（写库即刷新规则缓存） */
  createRule(body: AlarmRuleSaveReq): Promise<number> {
    return http.post('/api/alarm/rule', body)
  },

  /** PUT /api/alarm/rule/{ruleId} 修改告警规则（rule_code 不可改） */
  updateRule(ruleId: string, body: AlarmRuleSaveReq): Promise<void> {
    return http.put(`/api/alarm/rule/${ruleId}`, body)
  },

  /** DELETE /api/alarm/rule/{ruleId} 删除告警规则 */
  deleteRule(ruleId: string): Promise<void> {
    return http.delete(`/api/alarm/rule/${ruleId}`)
  },
}
