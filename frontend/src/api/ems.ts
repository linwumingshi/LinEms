import http from './http'
import type { EmsStrategy, EmsPlan, EmsPlanPoint, EmsExecutionRecord, EmsConstraint, EmsElectricityPrice, PageResult, RevenueSummary, RevenueTrendPoint, RevenueDetailRow, EmsStationMeta } from '@/types/models'

/** EMS 储能策略 API（网关路由 /api/ems/** → energy-ems） */
export const emsApi = {
  /** GET /api/ems/strategy/page 策略分页查询 */
  strategyPage(params: Record<string, unknown>): Promise<PageResult<EmsStrategy>> {
    return http.get('/api/ems/strategy/page', { params })
  },

  /** POST /api/ems/strategy 创建策略 */
  strategyCreate(body: Partial<EmsStrategy>): Promise<EmsStrategy> { return http.post('/api/ems/strategy', body) },

  /** PUT /api/ems/strategy/{id} 更新策略 */
  strategyUpdate(id: string, body: Partial<EmsStrategy>): Promise<EmsStrategy> { return http.put(`/api/ems/strategy/${id}`, body) },

  /** DELETE /api/ems/strategy/{id} 删除策略 */
  strategyDelete(id: string): Promise<void> { return http.delete(`/api/ems/strategy/${id}`) },

  /** PUT /api/ems/strategy/{id}/status 启停策略 */
  strategySwitchStatus(id: string, status: number): Promise<void> { return http.put(`/api/ems/strategy/${id}/status?status=${status}`) },

  /** GET /api/ems/price/page 电价分页查询 */
  pricePage(params: Record<string, unknown>): Promise<PageResult<EmsElectricityPrice>> { return http.get('/api/ems/price/page', { params }) },

  /** POST /api/ems/price 批量保存电价（upsert 幂等：同站同 startTime 原位更新） */
  priceSave(body: EmsElectricityPrice[]): Promise<void> { return http.post('/api/ems/price', body) },

  /** PUT /api/ems/price/{id} 更新电价档位 */
  priceUpdate(id: string, body: Partial<EmsElectricityPrice>): Promise<void> { return http.put(`/api/ems/price/${id}`, body) },

  /** DELETE /api/ems/price/{id} 删除电价档位 */
  priceDelete(id: string): Promise<void> { return http.delete(`/api/ems/price/${id}`) },

  /** GET /api/ems/constraint 查询站点约束 */
  constraintGet(stationId: string): Promise<EmsConstraint> { return http.get(`/api/ems/constraint?stationId=${stationId}`) },

  /** PUT /api/ems/constraint 保存站点约束 */
  constraintSave(body: EmsConstraint): Promise<EmsConstraint> { return http.put('/api/ems/constraint', body) },

  /** POST /api/ems/plan/generate 生成调度计划 */
  planGenerate(body: { stationId: string; strategyId?: string; planDate: string }): Promise<EmsPlan> {
    return http.post('/api/ems/plan/generate', body)
  },

  /** GET /api/ems/plan/page 计划分页查询 */
  planPage(params: Record<string, unknown>): Promise<PageResult<EmsPlan>> { return http.get('/api/ems/plan/page', { params }) },

  /** GET /api/ems/plan/{planId}/points 计划点位（充放电曲线） */
  planPoints(planId: string): Promise<EmsPlanPoint[]> { return http.get(`/api/ems/plan/${planId}/points`) },

  /** GET /api/ems/plan/{planId}/records 计划执行记录（点级下发/ACK 结果） */
  planRecords(planId: string): Promise<EmsExecutionRecord[]> { return http.get(`/api/ems/plan/${planId}/records`) },

  /** POST /api/ems/plan/{planId}/dispatch 下发调度计划 */
  dispatch(planId: string): Promise<number> { return http.post(`/api/ems/plan/${planId}/dispatch`) },

  /** GET /api/ems/revenue/summary 时段收益卡片 */
  revenueSummary(params: Record<string, unknown>): Promise<RevenueSummary> { return http.get('/api/ems/revenue/summary', { params }) },

  /** GET /api/ems/revenue/trend 收益趋势曲线（月按日、年按月） */
  revenueTrend(params: Record<string, unknown>): Promise<RevenueTrendPoint[]> { return http.get('/api/ems/revenue/trend', { params }) },

  /** GET /api/ems/revenue/detail 单日逐槽明细 */
  revenueDetail(params: Record<string, unknown>): Promise<RevenueDetailRow[]> { return http.get('/api/ems/revenue/detail', { params }) },

  /** GET /api/ems/revenue/meta 电站投资元数据（未配置返回 null） */
  revenueMetaGet(stationId: string): Promise<EmsStationMeta | null> { return http.get('/api/ems/revenue/meta', { params: { stationId } }) },

  /** PUT /api/ems/revenue/meta 保存电站投资元数据 */
  revenueMetaPut(body: Partial<EmsStationMeta>): Promise<EmsStationMeta> { return http.put('/api/ems/revenue/meta', body) },
}
