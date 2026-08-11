/**
 * 策略配置 JSON 的解析 / 校验 / 序列化（纯函数，无 Vue 依赖）。
 * schema 契约与后端 PlanGenerator.java 解析规则对齐（P0-4）：
 *   - PEAK_VALLEY / DEMAND 结构化窗口 {start:"HH:mm", end:"HH:mm", powerLimit}，end 排他（t.isBefore(end)）
 *   - TIME 结构化 schedule 时间段 {start, end, action, power}，至少一个充/放时段
 *   - DR / SOC_CTRL 仅要求合法 JSON 对象（生成期不可独立产点，标注不可生成）
 *   - start>=end 后端静默 0 点、powerLimit<=0 按 0 功率、空窗口仅 STANDBY 尾点 → 前端提前拦截
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

/** PEAK_VALLEY 结构化校验：窗口数组、字段格式、start<end、powerLimit>0。空窗口列表允许——「至少一个充电或放电窗口」由 validatePeakValleySaveable 在保存时强制。电价驱动（priceDriven=true）窗口可空：缺省数组视作空数组（generator 不读窗口，功率回退包络）。 */
export function parsePeakValleyConfig(value: unknown): ParsePeakValleyResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { ok: false, error: '缺少 chargeWindows 或 dischargeWindows 数组' }
  }
  const obj = value as Record<string, unknown>
  const priceDriven = obj.priceDriven === true
  if (!Array.isArray(obj.chargeWindows) || !Array.isArray(obj.dischargeWindows)) {
    if (!priceDriven) return { ok: false, error: '缺少 chargeWindows 或 dischargeWindows 数组' }
  }
  const chargeList = Array.isArray(obj.chargeWindows) ? obj.chargeWindows : []
  const chargeWindows: TimeWindow[] = []
  for (let i = 0; i < chargeList.length; i++) {
    const err = checkWindow(chargeList[i], i + 1)
    if (err) return { ok: false, error: err }
    chargeWindows.push(chargeList[i] as TimeWindow)
  }
  const dischargeList = Array.isArray(obj.dischargeWindows) ? obj.dischargeWindows : []
  const dischargeWindows: TimeWindow[] = []
  for (let i = 0; i < dischargeList.length; i++) {
    const err = checkWindow(dischargeList[i], i + 1)
    if (err) return { ok: false, error: err }
    dischargeWindows.push(dischargeList[i] as TimeWindow)
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

/** TIME 策略 schedule 时间段：显式动作 + 功率（STANDBY 段忽略 power）。 */
export interface TimeSlot {
  /** ISO LocalTime "HH:mm"，排他 */
  start: string
  /** ISO LocalTime "HH:mm"，排他 */
  end: string
  /** 段动作：CHARGE/DISCHARGE/STANDBY */
  action: 'CHARGE' | 'DISCHARGE' | 'STANDBY'
  /** 功率 kW，>0；STANDBY 段缺省合法 */
  power?: number
}

export interface TimeConfig {
  schedule: TimeSlot[]
}

export type ParseTimeResult =
  | { ok: true; config: TimeConfig; rest: Record<string, unknown> }
  | { ok: false; error: string }

/** TIME 结构化解析：schedule 时段 schema 校验（可空数组，供表单往返；"至少一个充/放时段"由 validateTimeConfig 强制）。 */
export function parseTimeConfig(value: unknown): ParseTimeResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { ok: false, error: '缺少 schedule 时间段数组' }
  }
  const obj = value as Record<string, unknown>
  const schedule = obj.schedule
  if (!Array.isArray(schedule)) return { ok: false, error: '缺少 schedule 时间段数组' }
  const slots: TimeSlot[] = []
  for (let i = 0; i < schedule.length; i++) {
    const s = schedule[i]
    const idx = i + 1
    if (typeof s !== 'object' || s === null) return { ok: false, error: `时段 ${idx} 的开始/结束时间格式应为 HH:mm` }
    const { start, end, action, power } = s as Record<string, unknown>
    if (typeof start !== 'string' || !HHMM_RE.test(start)) return { ok: false, error: `时段 ${idx} 的开始/结束时间格式应为 HH:mm` }
    if (typeof end !== 'string' || !HHMM_RE.test(end)) return { ok: false, error: `时段 ${idx} 的开始/结束时间格式应为 HH:mm` }
    if (start >= end) return { ok: false, error: `时段 ${idx} 的结束时间必须晚于开始时间` }
    if (typeof action !== 'string' || !TIME_ACTIONS.includes(action)) {
      return { ok: false, error: `时段 ${idx} 的 action 必须为 CHARGE/DISCHARGE/STANDBY` }
    }
    const slot: TimeSlot = { start, end, action: action as TimeSlot['action'] }
    if (action !== 'STANDBY') {
      if (typeof power !== 'number' || !(power > 0)) return { ok: false, error: `时段 ${idx} 的功率 power 必须大于 0` }
      slot.power = power
    }
    slots.push(slot)
  }
  const rest: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(obj)) if (k !== 'schedule') rest[k] = v
  return { ok: true, config: { schedule: slots }, rest }
}

/** 序列化：{ ...rest, schedule }，未知顶层键（如 socRange）原样保留。 */
export function serializeTime(config: TimeConfig, rest: Record<string, unknown>): string {
  return JSON.stringify({ ...rest, schedule: config.schedule }, null, 2)
}

/** 需量策略：谷段充电备能 + 需量时段放电削峰。窗口形状同峰谷（parsePeakValleyConfig 复用）；demandLimit 可选但须 >0（供 P1-2 需量管理消费）。 */
export function validateDemandConfig(config: string): string[] {
  const parsed = parseJsonConfig(config)
  if (!parsed.ok) return [parsed.error]
  const structured = parsePeakValleyConfig(parsed.value)
  if (!structured.ok) return [structured.error]
  if (structured.config.chargeWindows.length === 0 && structured.config.dischargeWindows.length === 0) {
    return ['请至少配置一个充电或放电窗口']
  }
  if ('demandLimit' in structured.rest) {
    const limit = structured.rest.demandLimit
    if (typeof limit !== 'number' || !(limit > 0)) return ['需量限值 demandLimit 必须大于 0']
  }
  return []
}

const TIME_ACTIONS = ['CHARGE', 'DISCHARGE', 'STANDBY']

/** 时间策略：schedule 时间段数组，每段 {start, end, action, power}；至少一个充/放时段，STANDBY 段忽略功率。 */
export function validateTimeConfig(config: string): string[] {
  const parsed = parseJsonConfig(config)
  if (!parsed.ok) return [parsed.error]
  const structured = parseTimeConfig(parsed.value)
  if (!structured.ok) return [structured.error]
  const { schedule } = structured.config
  if (schedule.length === 0) return ['缺少 schedule 时间段数组']
  if (!schedule.some((s) => s.action !== 'STANDBY')) return ['请至少配置一个充电或放电时段']
  return []
}

/** 按策略类型分发的保存期校验（EmsStrategy.vue save() 闸共用）：返回问题列表（空 = 通过）。与后端 StrategyConfigValidator.java 对齐。 */
export function validateStrategyConfig(config: string, strategyType?: string): string[] {
  if (strategyType === 'PEAK_VALLEY') return validatePeakValleySaveable(config)
  if (strategyType === 'DEMAND') return validateDemandConfig(config)
  if (strategyType === 'TIME') return validateTimeConfig(config)
  // DR（事件驱动）/SOC_CTRL（约束型）：不可生成（P0-4），仅要求非空合法 JSON 对象
  if (!config.trim()) return ['请填写配置 JSON']
  const parsed = parseJsonConfig(config)
  if (!parsed.ok) return [parsed.error]
  if (typeof parsed.value !== 'object' || parsed.value === null || Array.isArray(parsed.value)) {
    return ['配置必须是一个 JSON 对象']
  }
  return []
}
