import http from './http'
import type { EmsStrategy } from '@/types/models'

export const emsApi = {
  strategyPage(params: Record<string, unknown>) {
    return http.get('/api/ems/strategy/page', { params })
  },
  strategyCreate(body: Partial<EmsStrategy>) { return http.post('/api/ems/strategy', body) },
  strategyUpdate(id: number, body: Partial<EmsStrategy>) { return http.put(`/api/ems/strategy/${id}`, body) },
  strategyDelete(id: number) { return http.delete(`/api/ems/strategy/${id}`) },
  strategySwitchStatus(id: number, status: number) { return http.put(`/api/ems/strategy/${id}/status?status=${status}`) },
  pricePage(params: Record<string, unknown>) { return http.get('/api/ems/price/page', { params }) },
  priceSave(body: unknown[]) { return http.post('/api/ems/price', body) },
  constraintGet(stationId: number) { return http.get(`/api/ems/constraint?stationId=${stationId}`) },
  constraintSave(body: unknown) { return http.put('/api/ems/constraint', body) },
  planGenerate(body: { stationId: number; strategyId?: number; planDate: string }) {
    return http.post('/api/ems/plan/generate', body)
  },
  planPage(params: Record<string, unknown>) { return http.get('/api/ems/plan/page', { params }) },
  planPoints(planId: number) { return http.get(`/api/ems/plan/${planId}/points`) },
}
