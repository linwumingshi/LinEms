/** 统一状态/类型字典：管理页表格标签与表单下拉共用，未知值回退 未知(N) */

export function productStatusText(s: number): string {
  return s === 1 ? '启用' : s === 0 ? '禁用' : `未知(${s})`
}

const DEVICE_STATUS_TEXT: Record<number, string> = { 0: '未注册', 1: '未激活', 2: '离线', 3: '在线', 4: '禁用', 5: '封禁' }
const DEVICE_STATUS_TAG: Record<number, 'info' | 'primary' | 'success' | 'danger' | ''> = {
  0: '', 1: 'primary', 2: 'info', 3: 'success', 4: 'danger', 5: 'danger',
}
export function deviceStatusText(s: number): string { return DEVICE_STATUS_TEXT[s] ?? `未知(${s})` }
export function deviceStatusTag(s: number): 'info' | 'primary' | 'success' | 'danger' | '' { return DEVICE_STATUS_TAG[s] ?? 'info' }

export function userStatusText(s: number): string {
  return s === 0 ? '禁用' : s === 1 ? '启用' : s === 2 ? '锁定' : `未知(${s})`
}
export function userStatusTag(s: number): 'danger' | 'success' | 'info' {
  return s === 0 ? 'danger' : s === 1 ? 'success' : 'info'
}

export function roleStatusText(s: number): string { return s === 1 ? '启用' : s === 0 ? '停用' : `未知(${s})` }
export function roleStatusTag(s: number): 'danger' | 'success' { return s === 1 ? 'success' : 'danger' }

export function dataScopeText(s: number): string {
  return s === 1 ? '本人' : s === 2 ? '本企业' : s === 3 ? '本租户' : s === 4 ? '全部' : `未知(${s})`
}

export function permTypeText(s: number): string {
  return s === 1 ? '菜单' : s === 2 ? '按钮' : s === 3 ? '数据' : `未知(${s})`
}

export function thingModelStatusText(s: number): string {
  return s === 0 ? '草稿' : s === 1 ? '已发布' : s === 2 ? '已废弃' : `未知(${s})`
}

export function authStatusText(s: number): string { return s === 1 ? '正常' : s === 2 ? '吊销' : `未知(${s})` }

export const deviceTypeOptions = ['ENERGY_CABINET', 'BATTERY_CLUSTER', 'PCS', 'BMS', 'EMS', 'EDGE_GW']
export const DEVICE_TYPE_TEXT: Record<string, string> = {
  ENERGY_CABINET: '储能柜',
  BATTERY_CLUSTER: '电池簇',
  PCS: '变流器 PCS',
  BMS: '电池管理系统 BMS',
  EMS: '能量管理系统 EMS',
  EDGE_GW: '边缘网关',
}
/** 设备类型中文（未知回退原 code；空回退 '—'） */
export function deviceTypeText(t?: string | null): string {
  return t ? (DEVICE_TYPE_TEXT[t] ?? t) : '—'
}
/** 下拉 label：中文 (CODE) */
export function deviceTypeLabel(t: string): string {
  return `${DEVICE_TYPE_TEXT[t] ?? t} (${t})`
}

export const RESOURCE_TYPE_TEXT: Record<string, string> = {
  DEVICE: '设备',
  STRATEGY: '策略',
  ALARM: '告警',
  STATION: '电站',
}
/** 资源类型中文（未知回退原 code；空回退「不限」） */
export function resourceTypeText(t?: string | null): string {
  return t ? (RESOURCE_TYPE_TEXT[t] ?? t) : '不限'
}

/** 可生成调度计划的策略类型（与后端 PlanGenerator.java 支持集合对齐；后端新增支持时同步此数组） */
export const STRATEGY_GENERATABLE_TYPES: string[] = ['PEAK_VALLEY', 'DEMAND', 'TIME']
export function isStrategyGeneratable(type?: string): boolean {
  return !!type && STRATEGY_GENERATABLE_TYPES.includes(type)
}

/** 分时电价类型：中文 + 标签色（与后端 ems_electricity_price.price_type / PlanGenerator 档位推导对齐） */
export const PRICE_TYPE_TEXT: Record<string, string> = {
  DEEP: '深谷',
  VALLEY: '低谷',
  FLAT: '平段',
  PEAK: '高峰',
  PEEK: '尖峰',
}
export const PRICE_TYPE_TAG: Record<string, 'info' | 'primary' | 'success' | 'warning' | 'danger'> = {
  DEEP: 'primary',
  VALLEY: 'success',
  FLAT: 'info',
  PEAK: 'warning',
  PEEK: 'danger',
}
/** 电价类型中文（未知回退原 code；空回退 '—'） */
export function priceTypeText(t?: string | null): string {
  return t ? (PRICE_TYPE_TEXT[t] ?? t) : '—'
}
export function priceTypeTag(t?: string | null): 'info' | 'primary' | 'success' | 'warning' | 'danger' {
  return t ? (PRICE_TYPE_TAG[t] ?? 'info') : 'info'
}

/** 电站电网类型下拉（后端 Station.gridType 备注：工商业/园区/电网侧） */
export const GRID_TYPE_OPTIONS = ['工商业', '园区', '电网侧']

/** 电站状态：0 停运 1 运行 */
export function stationStatusText(s: number): string {
  return s === 1 ? '运行' : s === 0 ? '停运' : `未知(${s})`
}
export function stationStatusTag(s: number): 'success' | 'info' {
  return s === 1 ? 'success' : 'info'
}
