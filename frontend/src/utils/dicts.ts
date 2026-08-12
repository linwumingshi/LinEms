/**
 * 统一状态/类型字典：管理页表格标签与表单下拉共用，未知值回退 未知(N)。
 * 文案/标签色从 enums.ts 的 XX_TEXT/XX_TAG Record 取值，函数签名保持 number/string 兼容。
 */
import {
  CREDENTIAL_AUTH_STATUS_TEXT, CREDENTIAL_AUTH_STATUS_TAG,
  DATA_SCOPE_TEXT, DEVICE_STATUS_TAG, DEVICE_STATUS_TEXT, DEVICE_TYPE_TEXT,
  DeviceType, GridType, PERM_TYPE_TEXT, PRICE_TYPE_TAG, PRICE_TYPE_TEXT, PRODUCT_STATUS_TEXT,
  RoleStatus, ROLE_STATUS_TAG, ROLE_STATUS_TEXT, STATION_STATUS_TAG,
  STATION_STATUS_TEXT, STRATEGY_GENERATABLE_TYPES as STRATEGY_GENERATABLE_TYPES_ENUM,
  THING_MODEL_STATUS_TEXT, UserStatus, USER_STATUS_TAG, USER_STATUS_TEXT,
} from '@/utils/enums'

export function productStatusText(s: number): string {
  return PRODUCT_STATUS_TEXT[s as keyof typeof PRODUCT_STATUS_TEXT] ?? `未知(${s})`
}

export function deviceStatusText(s: number): string {
  return DEVICE_STATUS_TEXT[s as keyof typeof DEVICE_STATUS_TEXT] ?? `未知(${s})`
}
export function deviceStatusTag(s: number): 'info' | 'primary' | 'success' | 'danger' | '' {
  return DEVICE_STATUS_TAG[s as keyof typeof DEVICE_STATUS_TAG] ?? 'info'
}

export function userStatusText(s: number): string {
  return USER_STATUS_TEXT[s as keyof typeof USER_STATUS_TEXT] ?? `未知(${s})`
}
export function userStatusTag(s: number): 'danger' | 'success' | 'info' {
  return USER_STATUS_TAG[s as UserStatus] ?? 'info'
}

export function roleStatusText(s: number): string {
  return ROLE_STATUS_TEXT[s as RoleStatus] ?? `未知(${s})`
}
export function roleStatusTag(s: number): 'danger' | 'success' {
  return ROLE_STATUS_TAG[s as RoleStatus] ?? 'danger'
}

export function dataScopeText(s: number): string {
  return DATA_SCOPE_TEXT[s as keyof typeof DATA_SCOPE_TEXT] ?? `未知(${s})`
}

export function permTypeText(s: number): string {
  return PERM_TYPE_TEXT[s as keyof typeof PERM_TYPE_TEXT] ?? `未知(${s})`
}

export function thingModelStatusText(s: number): string {
  return THING_MODEL_STATUS_TEXT[s as keyof typeof THING_MODEL_STATUS_TEXT] ?? `未知(${s})`
}

export function authStatusText(s: number): string {
  return CREDENTIAL_AUTH_STATUS_TEXT[s as keyof typeof CREDENTIAL_AUTH_STATUS_TEXT] ?? `未知(${s})`
}
export function authStatusTag(s: number): 'success' | 'danger' {
  return CREDENTIAL_AUTH_STATUS_TAG[s as keyof typeof CREDENTIAL_AUTH_STATUS_TAG] ?? 'danger'
}

export const deviceTypeOptions: string[] = Object.values(DeviceType)
/** 设备类型中文（未知回退原 code；空回退 '—'） */
export function deviceTypeText(t?: string | null): string {
  return t ? (DEVICE_TYPE_TEXT[t as keyof typeof DEVICE_TYPE_TEXT] ?? t) : '—'
}
/** 下拉 label：中文 (CODE) */
export function deviceTypeLabel(t: string): string {
  return `${DEVICE_TYPE_TEXT[t as keyof typeof DEVICE_TYPE_TEXT] ?? t} (${t})`
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
export const STRATEGY_GENERATABLE_TYPES: string[] = [...STRATEGY_GENERATABLE_TYPES_ENUM]
export function isStrategyGeneratable(type?: string): boolean {
  return !!type && STRATEGY_GENERATABLE_TYPES.includes(type)
}

/** 电价类型中文（未知回退原 code；空回退 '—'） */
export function priceTypeText(t?: string | null): string {
  return t ? (PRICE_TYPE_TEXT[t as keyof typeof PRICE_TYPE_TEXT] ?? t) : '—'
}
export function priceTypeTag(t?: string | null): 'info' | 'primary' | 'success' | 'warning' | 'danger' {
  return t ? (PRICE_TYPE_TAG[t as keyof typeof PRICE_TYPE_TAG] ?? 'info') : 'info'
}

/** 电站电网类型下拉（后端 Station.gridType：工商业/园区/电网侧） */
export const GRID_TYPE_OPTIONS: string[] = Object.values(GridType)

/** 电站状态：0 停运 1 运行 */
export function stationStatusText(s: number): string {
  return STATION_STATUS_TEXT[s as keyof typeof STATION_STATUS_TEXT] ?? `未知(${s})`
}
export function stationStatusTag(s: number): 'success' | 'info' {
  return STATION_STATUS_TAG[s as keyof typeof STATION_STATUS_TAG] ?? 'info'
}
