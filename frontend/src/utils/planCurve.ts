import { tsdbApi } from '@/api/tsdb'

/**
 * 拉取计划日实际功率曲线（TSDB property/history，asc 排序）
 * 失败抛错由调用方降级
 */
export async function fetchActualCurve(
  deviceId: string,
  productKey: string,
  // YYYY-MM-DD
  planDate: string,
): Promise<{ times: string[]; power: number[] }> {
  const start = new Date(`${planDate}T00:00:00`).getTime()
  const end = new Date(`${planDate}T23:59:59`).getTime()
  const view = await tsdbApi.propertyHistory({
    deviceId,
    productKey,
    identifiers: ['power'],
    startTime: start,
    endTime: end,
    order: 'asc',
    page: 1,
    size: 2000,
  })
  const times: string[] = []
  const power: number[] = []
  // 后端每行 { ts, values }；values 缺键表示该时刻未上报该属性，按 0 处理
  for (const row of view.records) {
    times.push(new Date(row.ts).toTimeString().slice(0, 5))
    power.push(Number(row.values.power ?? 0))
  }
  return { times, power }
}

/**
 * 多台 PCS 实际功率曲线按时刻聚合：同刻功率求和，时间并集升序（P0-2 一电站多 PCS）。
 * 0 台返回空曲线；1 台原样返回。空曲线由调用方当 null 处理。
 */
export function mergeCurves(curves: { times: string[]; power: number[] }[]): { times: string[]; power: number[] } {
  if (curves.length <= 1) return curves[0] ?? { times: [], power: [] }
  const byTime = new Map<string, number>()
  for (const c of curves) {
    c.times.forEach((t, i) => byTime.set(t, (byTime.get(t) ?? 0) + Number(c.power[i] ?? 0)))
  }
  const times = [...byTime.keys()].sort()
  return { times, power: times.map((t) => byTime.get(t)!) }
}
