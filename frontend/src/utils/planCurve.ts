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
