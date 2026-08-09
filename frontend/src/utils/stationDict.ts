/**
 * 电站列表加载与名称解析（纯逻辑，Vue 无关）。
 * 模块级缓存避免策略页/计划页重复请求；名称查不到回退裸 id，绝不空白。
 * 后端契约：GET /api/station/page 分页参数是 pageNum（StationQuery），非 EMS 的 pageNo。
 */
import { stationApi } from '@/api/station'
import type { Station } from '@/types/models'

let cached: Station[] | null = null

/** 电站列表（首次拉取后缓存；force=true 强制重拉）。失败向上抛，由调用方决定提示策略。 */
export async function loadStations(force = false): Promise<Station[]> {
  if (cached && !force) return cached
  const page = await stationApi.stationPage({ pageNum: 1, pageSize: 100 })
  cached = page.records
  return cached
}

/** 测试用：清空缓存 */
export function _resetStationCache(): void {
  cached = null
}

/** 名称解析：已知 id → stationName；未知 → 回退原 id；空 → 空串。 */
export function stationName(id: string | number | null | undefined, stations: Station[]): string {
  const key = String(id ?? '')
  if (!key) return ''
  return stations.find((s) => String(s.stationId) === key)?.stationName ?? key
}
