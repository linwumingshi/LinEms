import http from './http'
import type { PageResult, RuleLogView, RuleSaveReq, RuleView } from '@/types/models'

export interface RulePageParams {
  ruleName?: string
  enabled?: number
  page: number
  size: number
}

export interface RuleLogParams {
  ruleId?: string
  triggerType?: string
  deviceId?: string
  startTime?: string
  endTime?: string
  page: number
  size: number
}

/** 场景联动 API（网关路由 /api/rule/** → energy-rule，Phase 11） */
export const ruleApi = {
  /** GET /api/rule/page 规则分页查询 */
  page(params: RulePageParams): Promise<PageResult<RuleView>> {
    return http.get('/api/rule/page', { params })
  },

  /** GET /api/rule/{ruleId} 规则详情 */
  get(ruleId: number): Promise<RuleView> {
    return http.get(`/api/rule/${ruleId}`)
  },

  /** POST /api/rule 创建规则（DSL 校验 + 防环） */
  create(req: RuleSaveReq): Promise<RuleView> {
    return http.post('/api/rule', req)
  },

  /** PUT /api/rule/{ruleId} 更新规则（乐观锁 version） */
  update(ruleId: number, req: RuleSaveReq): Promise<RuleView> {
    return http.put(`/api/rule/${ruleId}`, req)
  },

  /** DELETE /api/rule/{ruleId} 删除规则 */
  remove(ruleId: number): Promise<void> {
    return http.delete(`/api/rule/${ruleId}`)
  },

  /** POST /api/rule/{ruleId}/enable 启用 */
  enable(ruleId: number): Promise<void> {
    return http.post(`/api/rule/${ruleId}/enable`)
  },

  /** POST /api/rule/{ruleId}/disable 停用 */
  disable(ruleId: number): Promise<void> {
    return http.post(`/api/rule/${ruleId}/disable`)
  },

  /** POST /api/rule/{ruleId}/trigger 手动触发（body 载荷注入上下文） */
  trigger(ruleId: number, payload?: Record<string, unknown>): Promise<void> {
    return http.post(`/api/rule/${ruleId}/trigger`, payload && Object.keys(payload).length > 0 ? { payload } : {})
  },

  /** GET /api/rule/log/page 执行日志分页 */
  logPage(params: RuleLogParams): Promise<PageResult<RuleLogView>> {
    return http.get('/api/rule/log/page', { params })
  },
}
