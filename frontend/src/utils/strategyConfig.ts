/**
 * 策略配置 JSON 的解析 / 校验 / 序列化（纯函数，无 Vue 依赖）。
 * schema 契约与后端 PlanGenerator.java 解析规则对齐：
 *   - 仅 PEAK_VALLEY 有结构化 schema；窗口 {start:"HH:mm", end:"HH:mm", powerLimit}，end 排他（t.isBefore(end)）
 *   - start>=end 后端静默 0 点、powerLimit<=0 按 0 功率、空窗口仅 STANDBY 尾点（PlanGenerator.java:45,55,62）→ 前端提前拦截
 *   - socRange 等未知顶层键为死字段（生成器不读），序列化时经 rest 原样保留
 */

export interface TimeWindow {
  /** ISO LocalTime "HH:mm"，如 "02:00" */
  start: string
  /** ISO LocalTime "HH:mm"，排他（生成器 t.isBefore(end)） */
  end: string
  /** 窗口功率上限 kW，>0 */
  powerLimit: number
}

export interface PeakValleyConfig {
  chargeWindows: TimeWindow[]
  dischargeWindows: TimeWindow[]
}

export type ParseJsonResult =
  | { ok: true; value: unknown }
  | { ok: false; error: string }

export type ParsePeakValleyResult =
  | { ok: true; config: PeakValleyConfig; rest: Record<string, unknown> }
  | { ok: false; error: string }

/** "HH:mm"（0-23 时、0-59 分，零填充）。00:00 <= s < "24:00"，字典序即时序序。 */
const HHMM_RE = /^([01]\d|2[0-3]):[0-5]\d$/

/** 通用 JSON 语法校验（所有类型的 JSON 模式共用；空串/非法返回 error）。 */
export function parseJsonConfig(config: string): ParseJsonResult {
  try {
    return { ok: true, value: JSON.parse(config) }
  } catch (e) {
    return { ok: false, error: `配置不是合法 JSON：${e instanceof Error ? e.message : String(e)}` }
  }
}

/** 校验单个窗口（序号从 1 起）。返回首个错误文案；null = 通过。 */
function checkWindow(w: unknown, i: number): string | null {
  if (typeof w !== 'object' || w === null) return `窗口 ${i} 的开始/结束时间格式应为 HH:mm`
  const obj = w as Record<string, unknown>
  const { start, end, powerLimit } = obj
  if (typeof start !== 'string' || !HHMM_RE.test(start)) return `窗口 ${i} 的开始/结束时间格式应为 HH:mm`
  if (typeof end !== 'string' || !HHMM_RE.test(end)) return `窗口 ${i} 的开始/结束时间格式应为 HH:mm`
  if (start >= end) return `窗口 ${i} 的结束时间必须晚于开始时间`
  if (typeof powerLimit !== 'number' || !(powerLimit > 0)) return `窗口 ${i} 的功率上限必须大于 0`
  return null
}

/** PEAK_VALLEY 结构化校验：窗口数组、字段格式、start<end、powerLimit>0。空窗口列表允许——「至少一个充电或放电窗口」由 validatePeakValleySaveable 在保存时强制。 */
export function parsePeakValleyConfig(value: unknown): ParsePeakValleyResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { ok: false, error: '缺少 chargeWindows 或 dischargeWindows 数组' }
  }
  const obj = value as Record<string, unknown>
  if (!Array.isArray(obj.chargeWindows) || !Array.isArray(obj.dischargeWindows)) {
    return { ok: false, error: '缺少 chargeWindows 或 dischargeWindows 数组' }
  }
  const chargeWindows: TimeWindow[] = []
  for (let i = 0; i < obj.chargeWindows.length; i++) {
    const err = checkWindow(obj.chargeWindows[i], i + 1)
    if (err) return { ok: false, error: err }
    chargeWindows.push(obj.chargeWindows[i] as TimeWindow)
  }
  const dischargeWindows: TimeWindow[] = []
  for (let i = 0; i < obj.dischargeWindows.length; i++) {
    const err = checkWindow(obj.dischargeWindows[i], i + 1)
    if (err) return { ok: false, error: err }
    dischargeWindows.push(obj.dischargeWindows[i] as TimeWindow)
  }
  const rest: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(obj)) {
    if (k !== 'chargeWindows' && k !== 'dischargeWindows') rest[k] = v
  }
  return { ok: true, config: { chargeWindows, dischargeWindows }, rest }
}

/** 便捷入口：config 字符串 → 问题列表（空数组 = 通过）。save() 闸与组件内联提示共用。 */
export function validatePeakValleyConfig(config: string): string[] {
  const parsed = parseJsonConfig(config)
  if (!parsed.ok) return [parsed.error]
  const structured = parsePeakValleyConfig(parsed.value)
  if (!structured.ok) return [structured.error]
  return []
}

/** 保存闸：结构校验（validatePeakValleyConfig）+ 至少一个窗口。电价驱动（priceDriven=true）豁免——窗口可空（无 chargeWindows/dischargeWindows 数组也合法），功率缺省回退包络。 */
export function validatePeakValleySaveable(config: string): string[] {
  if (!config.trim()) return ['请至少配置一个充电或放电窗口'] // 空配置 ≠ 非法 JSON，先给「至少一个窗口」
  const parsed = parseJsonConfig(config)
  if (!parsed.ok) return [parsed.error] // 非法 JSON 先行
  const obj = parsed.value as {
    priceDriven?: boolean
    chargeWindows?: unknown[]
    dischargeWindows?: unknown[]
  } | null
  if (obj?.priceDriven === true) return [] // 电价驱动：窗口可空，功率缺省回退包络
  const issues = validatePeakValleyConfig(config)
  if (issues.length) return issues
  if ((obj?.chargeWindows?.length ?? 0) === 0 && (obj?.dischargeWindows?.length ?? 0) === 0) {
    return ['请至少配置一个充电或放电窗口']
  }
  return []
}

/** 序列化：{ ...rest, chargeWindows, dischargeWindows }，未知顶层键（含 socRange）原样保留。 */
export function serializePeakValley(config: PeakValleyConfig, rest: Record<string, unknown>): string {
  return JSON.stringify(
    { ...rest, chargeWindows: config.chargeWindows, dischargeWindows: config.dischargeWindows },
    null,
    2,
  )
}
