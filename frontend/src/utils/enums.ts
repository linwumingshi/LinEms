/**
 * 业务状态/分类枚举：与后端 com.energyx.common.enums 对齐，code 与 DB/接口值严格一致。
 * 运行时值仍是数字/字符串字面量，仅编译期类型约束；文案/标签色集中在 XX_TEXT/XX_TAG。
 * 说明：TEXT 与后端枚举 desc 对齐；TAG 为 Element Plus el-tag type（'' 为默认色），仅状态类提供。
 */

// ---------------- 设备 ----------------

export const DeviceStatus = {
  UNREGISTERED: 0,
  INACTIVE: 1,
  OFFLINE: 2,
  ONLINE: 3,
  DISABLED: 4,
  BANNED: 5,
} as const
export type DeviceStatus = (typeof DeviceStatus)[keyof typeof DeviceStatus]

export const DEVICE_STATUS_TEXT: Record<DeviceStatus, string> = {
  [DeviceStatus.UNREGISTERED]: '未注册',
  [DeviceStatus.INACTIVE]: '未激活',
  [DeviceStatus.OFFLINE]: '已激活离线',
  [DeviceStatus.ONLINE]: '在线',
  [DeviceStatus.DISABLED]: '禁用',
  [DeviceStatus.BANNED]: '封禁',
}
export const DEVICE_STATUS_TAG: Record<DeviceStatus, '' | 'primary' | 'info' | 'success' | 'danger'> = {
  [DeviceStatus.UNREGISTERED]: '',
  [DeviceStatus.INACTIVE]: 'primary',
  [DeviceStatus.OFFLINE]: 'info',
  [DeviceStatus.ONLINE]: 'success',
  [DeviceStatus.DISABLED]: 'danger',
  [DeviceStatus.BANNED]: 'danger',
}

export const DeviceType = {
  ENERGY_CABINET: 'ENERGY_CABINET',
  BATTERY_CLUSTER: 'BATTERY_CLUSTER',
  PCS: 'PCS',
  BMS: 'BMS',
  EMS: 'EMS',
  EDGE_GW: 'EDGE_GW',
  METER: 'METER',
} as const
export type DeviceType = (typeof DeviceType)[keyof typeof DeviceType]

export const DEVICE_TYPE_TEXT: Record<DeviceType, string> = {
  [DeviceType.ENERGY_CABINET]: '储能柜',
  [DeviceType.BATTERY_CLUSTER]: '电池簇',
  [DeviceType.PCS]: '变流器',
  [DeviceType.BMS]: '电池管理',
  [DeviceType.EMS]: '能量管理',
  [DeviceType.EDGE_GW]: '边缘网关',
  [DeviceType.METER]: '进线电能表',
}

export const CredentialAuthStatus = {
  NORMAL: 1,
  REVOKED: 2,
} as const
export type CredentialAuthStatus = (typeof CredentialAuthStatus)[keyof typeof CredentialAuthStatus]

export const CREDENTIAL_AUTH_STATUS_TEXT: Record<CredentialAuthStatus, string> = {
  [CredentialAuthStatus.NORMAL]: '正常',
  [CredentialAuthStatus.REVOKED]: '吊销',
}
export const CREDENTIAL_AUTH_STATUS_TAG: Record<CredentialAuthStatus, 'success' | 'danger'> = {
  [CredentialAuthStatus.NORMAL]: 'success',
  [CredentialAuthStatus.REVOKED]: 'danger',
}

// ---------------- 指令 ----------------

export const CommandState = {
  CREATED: 0,
  SENT: 1,
  DEVICE_RECEIVED: 2,
  EXECUTING: 3,
  SUCCESS: 4,
  FAILED: 5,
  TIMEOUT: 6,
} as const
export type CommandState = (typeof CommandState)[keyof typeof CommandState]

export const COMMAND_STATE_TEXT: Record<CommandState, string> = {
  [CommandState.CREATED]: '已创建',
  [CommandState.SENT]: '已发送',
  [CommandState.DEVICE_RECEIVED]: '设备已接收',
  [CommandState.EXECUTING]: '执行中',
  [CommandState.SUCCESS]: '成功',
  [CommandState.FAILED]: '失败',
  [CommandState.TIMEOUT]: '超时',
}
export const COMMAND_STATE_TAG: Record<CommandState, 'info' | 'primary' | 'warning' | 'success' | 'danger'> = {
  [CommandState.CREATED]: 'info',
  [CommandState.SENT]: 'primary',
  [CommandState.DEVICE_RECEIVED]: 'primary',
  [CommandState.EXECUTING]: 'warning',
  [CommandState.SUCCESS]: 'success',
  [CommandState.FAILED]: 'danger',
  [CommandState.TIMEOUT]: 'danger',
}

// ---------------- 告警 ----------------

export const AlarmLevel = {
  INFO: 1,
  GENERAL: 2,
  MAJOR: 3,
  CRITICAL: 4,
} as const
export type AlarmLevel = (typeof AlarmLevel)[keyof typeof AlarmLevel]

export const ALARM_LEVEL_TEXT: Record<AlarmLevel, string> = {
  [AlarmLevel.INFO]: '提示',
  [AlarmLevel.GENERAL]: '一般',
  [AlarmLevel.MAJOR]: '严重',
  [AlarmLevel.CRITICAL]: '危急',
}
export const ALARM_LEVEL_TAG: Record<AlarmLevel, 'info' | 'primary' | 'warning' | 'danger'> = {
  [AlarmLevel.INFO]: 'info',
  [AlarmLevel.GENERAL]: 'primary',
  [AlarmLevel.MAJOR]: 'warning',
  [AlarmLevel.CRITICAL]: 'danger',
}

export const AlarmRecordStatus = {
  TRIGGERING: 0,
  RECOVERED: 1,
  ACKED: 2,
} as const
export type AlarmRecordStatus = (typeof AlarmRecordStatus)[keyof typeof AlarmRecordStatus]

export const ALARM_RECORD_STATUS_TEXT: Record<AlarmRecordStatus, string> = {
  [AlarmRecordStatus.TRIGGERING]: '触发中',
  [AlarmRecordStatus.RECOVERED]: '已恢复',
  [AlarmRecordStatus.ACKED]: '已确认',
}
export const ALARM_RECORD_STATUS_TAG: Record<AlarmRecordStatus, 'danger' | 'success' | 'info'> = {
  [AlarmRecordStatus.TRIGGERING]: 'danger',
  [AlarmRecordStatus.RECOVERED]: 'success',
  [AlarmRecordStatus.ACKED]: 'info',
}

// ---------------- EMS 策略/计划/电价 ----------------

export const StrategyStatus = {
  DRAFT: 0,
  ENABLED: 1,
  DISABLED: 2,
} as const
export type StrategyStatus = (typeof StrategyStatus)[keyof typeof StrategyStatus]

export const STRATEGY_STATUS_TEXT: Record<StrategyStatus, string> = {
  [StrategyStatus.DRAFT]: '草稿',
  [StrategyStatus.ENABLED]: '启用',
  [StrategyStatus.DISABLED]: '停用',
}
export const STRATEGY_STATUS_TAG: Record<StrategyStatus, 'info' | 'success' | 'danger'> = {
  [StrategyStatus.DRAFT]: 'info',
  [StrategyStatus.ENABLED]: 'success',
  [StrategyStatus.DISABLED]: 'danger',
}

export const StrategyType = {
  PEAK_VALLEY: 'PEAK_VALLEY',
  DEMAND: 'DEMAND',
  DR: 'DR',
  SOC_CTRL: 'SOC_CTRL',
  TIME: 'TIME',
} as const
export type StrategyType = (typeof StrategyType)[keyof typeof StrategyType]

export const STRATEGY_TYPE_TEXT: Record<StrategyType, string> = {
  [StrategyType.PEAK_VALLEY]: '峰谷套利',
  [StrategyType.DEMAND]: '需量管理',
  [StrategyType.DR]: '需求响应',
  [StrategyType.SOC_CTRL]: 'SOC 约束',
  [StrategyType.TIME]: '时间策略',
}
export const STRATEGY_TYPE_TAG: Record<StrategyType, 'success' | 'primary' | 'warning' | 'info'> = {
  [StrategyType.PEAK_VALLEY]: 'success',
  [StrategyType.DEMAND]: 'primary',
  [StrategyType.DR]: 'warning',
  [StrategyType.SOC_CTRL]: 'info',
  [StrategyType.TIME]: 'info',
}

/** 可生成调度计划的策略类型集合（与后端 PlanGenerator.java 支持集合对齐；后端新增支持时同步） */
export const STRATEGY_GENERATABLE_TYPES: readonly StrategyType[] = [
  StrategyType.PEAK_VALLEY,
  StrategyType.DEMAND,
  StrategyType.TIME,
]

export const PlanStatus = {
  PENDING: 0,
  EXECUTING: 1,
  COMPLETED: 2,
  CANCELLED: 3,
  FAILED: 4,
} as const
export type PlanStatus = (typeof PlanStatus)[keyof typeof PlanStatus]

export const PLAN_STATUS_TEXT: Record<PlanStatus, string> = {
  [PlanStatus.PENDING]: '待执行',
  [PlanStatus.EXECUTING]: '执行中',
  [PlanStatus.COMPLETED]: '完成',
  [PlanStatus.CANCELLED]: '已取消',
  [PlanStatus.FAILED]: '失败',
}
export const PLAN_STATUS_TAG: Record<PlanStatus, 'info' | 'primary' | 'success' | 'danger'> = {
  [PlanStatus.PENDING]: 'info',
  [PlanStatus.EXECUTING]: 'primary',
  [PlanStatus.COMPLETED]: 'success',
  [PlanStatus.CANCELLED]: 'info',
  [PlanStatus.FAILED]: 'danger',
}

export const PlanPointState = {
  PENDING: 0,
  SENT: 1,
  SUCCESS: 2,
  FAILED: 3,
  TIMEOUT: 4,
} as const
export type PlanPointState = (typeof PlanPointState)[keyof typeof PlanPointState]

export const PLAN_POINT_STATE_TEXT: Record<PlanPointState, string> = {
  [PlanPointState.PENDING]: '待下发',
  [PlanPointState.SENT]: '已下发',
  [PlanPointState.SUCCESS]: '成功',
  [PlanPointState.FAILED]: '失败',
  [PlanPointState.TIMEOUT]: '超时',
}
export const PLAN_POINT_STATE_TAG: Record<PlanPointState, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  [PlanPointState.PENDING]: 'info',
  [PlanPointState.SENT]: 'primary',
  [PlanPointState.SUCCESS]: 'success',
  [PlanPointState.FAILED]: 'danger',
  [PlanPointState.TIMEOUT]: 'warning',
}

export const ElectricityPriceStatus = {
  DISABLED: 0,
  ENABLED: 1,
} as const
export type ElectricityPriceStatus = (typeof ElectricityPriceStatus)[keyof typeof ElectricityPriceStatus]

export const ELECTRICITY_PRICE_STATUS_TEXT: Record<ElectricityPriceStatus, string> = {
  [ElectricityPriceStatus.DISABLED]: '停用',
  [ElectricityPriceStatus.ENABLED]: '启用',
}
export const ELECTRICITY_PRICE_STATUS_TAG: Record<ElectricityPriceStatus, 'info' | 'success'> = {
  [ElectricityPriceStatus.DISABLED]: 'info',
  [ElectricityPriceStatus.ENABLED]: 'success',
}

export const PriceType = {
  DEEP: 'DEEP',
  VALLEY: 'VALLEY',
  FLAT: 'FLAT',
  PEAK: 'PEAK',
  PEEK: 'PEEK',
} as const
export type PriceType = (typeof PriceType)[keyof typeof PriceType]

export const PRICE_TYPE_TEXT: Record<PriceType, string> = {
  [PriceType.DEEP]: '深谷',
  [PriceType.VALLEY]: '低谷',
  [PriceType.FLAT]: '平段',
  [PriceType.PEAK]: '高峰',
  [PriceType.PEEK]: '尖峰',
}
export const PRICE_TYPE_TAG: Record<PriceType, 'info' | 'primary' | 'success' | 'warning' | 'danger'> = {
  [PriceType.DEEP]: 'primary',
  [PriceType.VALLEY]: 'success',
  [PriceType.FLAT]: 'info',
  [PriceType.PEAK]: 'warning',
  [PriceType.PEEK]: 'danger',
}

export const RevenuePeriodType = {
  DAY: 'DAY',
  MONTH: 'MONTH',
  YEAR: 'YEAR',
} as const
export type RevenuePeriodType = (typeof RevenuePeriodType)[keyof typeof RevenuePeriodType]

export const REVENUE_PERIOD_TYPE_TEXT: Record<RevenuePeriodType, string> = {
  [RevenuePeriodType.DAY]: '日',
  [RevenuePeriodType.MONTH]: '月',
  [RevenuePeriodType.YEAR]: '年',
}

// ---------------- 电站 ----------------

export const StationStatus = {
  STOPPED: 0,
  RUNNING: 1,
} as const
export type StationStatus = (typeof StationStatus)[keyof typeof StationStatus]

export const STATION_STATUS_TEXT: Record<StationStatus, string> = {
  [StationStatus.STOPPED]: '停运',
  [StationStatus.RUNNING]: '运行',
}
export const STATION_STATUS_TAG: Record<StationStatus, 'success' | 'info'> = {
  [StationStatus.STOPPED]: 'info',
  [StationStatus.RUNNING]: 'success',
}

export const GridType = {
  COMMERCIAL: '工商业',
  PARK: '园区',
  GRID_SIDE: '电网侧',
} as const
export type GridType = (typeof GridType)[keyof typeof GridType]

// ---------------- 产品 ----------------

export const ProductStatus = {
  DISABLED: 0,
  ENABLED: 1,
} as const
export type ProductStatus = (typeof ProductStatus)[keyof typeof ProductStatus]

export const PRODUCT_STATUS_TEXT: Record<ProductStatus, string> = {
  [ProductStatus.DISABLED]: '禁用',
  [ProductStatus.ENABLED]: '启用',
}
export const PRODUCT_STATUS_TAG: Record<ProductStatus, 'info' | 'success'> = {
  [ProductStatus.DISABLED]: 'info',
  [ProductStatus.ENABLED]: 'success',
}

export const ThingModelStatus = {
  DRAFT: 0,
  PUBLISHED: 1,
  DEPRECATED: 2,
} as const
export type ThingModelStatus = (typeof ThingModelStatus)[keyof typeof ThingModelStatus]

export const THING_MODEL_STATUS_TEXT: Record<ThingModelStatus, string> = {
  [ThingModelStatus.DRAFT]: '草稿',
  [ThingModelStatus.PUBLISHED]: '已发布',
  [ThingModelStatus.DEPRECATED]: '已废弃',
}
export const THING_MODEL_STATUS_TAG: Record<ThingModelStatus, 'info' | 'success' | 'danger'> = {
  [ThingModelStatus.DRAFT]: 'info',
  [ThingModelStatus.PUBLISHED]: 'success',
  [ThingModelStatus.DEPRECATED]: 'danger',
}

// ---------------- 系统 RBAC ----------------

export const UserStatus = {
  DISABLED: 0,
  ENABLED: 1,
  LOCKED: 2,
} as const
export type UserStatus = (typeof UserStatus)[keyof typeof UserStatus]

export const USER_STATUS_TEXT: Record<UserStatus, string> = {
  [UserStatus.DISABLED]: '禁用',
  [UserStatus.ENABLED]: '启用',
  [UserStatus.LOCKED]: '锁定',
}
export const USER_STATUS_TAG: Record<UserStatus, 'danger' | 'success' | 'info'> = {
  [UserStatus.DISABLED]: 'danger',
  [UserStatus.ENABLED]: 'success',
  [UserStatus.LOCKED]: 'info',
}

export const RoleStatus = {
  DISABLED: 0,
  ENABLED: 1,
} as const
export type RoleStatus = (typeof RoleStatus)[keyof typeof RoleStatus]

export const ROLE_STATUS_TEXT: Record<RoleStatus, string> = {
  [RoleStatus.DISABLED]: '禁用',
  [RoleStatus.ENABLED]: '启用',
}
export const ROLE_STATUS_TAG: Record<RoleStatus, 'danger' | 'success'> = {
  [RoleStatus.DISABLED]: 'danger',
  [RoleStatus.ENABLED]: 'success',
}

export const DataScope = {
  SELF: 1,
  ENTERPRISE: 2,
  TENANT: 3,
  ALL: 4,
} as const
export type DataScope = (typeof DataScope)[keyof typeof DataScope]

export const DATA_SCOPE_TEXT: Record<DataScope, string> = {
  [DataScope.SELF]: '本人',
  [DataScope.ENTERPRISE]: '本企业',
  [DataScope.TENANT]: '本租户',
  [DataScope.ALL]: '全部',
}

export const PermissionStatus = {
  NORMAL: 0,
  DISABLED: 1,
} as const
export type PermissionStatus = (typeof PermissionStatus)[keyof typeof PermissionStatus]

export const PERMISSION_STATUS_TEXT: Record<PermissionStatus, string> = {
  [PermissionStatus.NORMAL]: '正常',
  [PermissionStatus.DISABLED]: '停用',
}
export const PERMISSION_STATUS_TAG: Record<PermissionStatus, 'success' | 'danger'> = {
  [PermissionStatus.NORMAL]: 'success',
  [PermissionStatus.DISABLED]: 'danger',
}

export const TenantStatus = {
  DISABLED: 0,
  ENABLED: 1,
} as const
export type TenantStatus = (typeof TenantStatus)[keyof typeof TenantStatus]

export const TENANT_STATUS_TEXT: Record<TenantStatus, string> = {
  [TenantStatus.DISABLED]: '禁用',
  [TenantStatus.ENABLED]: '启用',
}
export const TENANT_STATUS_TAG: Record<TenantStatus, 'info' | 'success'> = {
  [TenantStatus.DISABLED]: 'info',
  [TenantStatus.ENABLED]: 'success',
}

/** 权限类型（后端 sys_permission.perm_type：1 菜单 2 按钮 3 数据） */
export const PermType = {
  MENU: 1,
  BUTTON: 2,
  DATA: 3,
} as const
export type PermType = (typeof PermType)[keyof typeof PermType]

export const PERM_TYPE_TEXT: Record<PermType, string> = {
  [PermType.MENU]: '菜单',
  [PermType.BUTTON]: '按钮',
  [PermType.DATA]: '数据',
}

// ---------------- 事件级别（接入域） ----------------

export const EventSeverity = {
  INFO: 'INFO',
  WARN: 'WARN',
  ERROR: 'ERROR',
  CRITICAL: 'CRITICAL',
} as const
export type EventSeverity = (typeof EventSeverity)[keyof typeof EventSeverity]

export const EVENT_SEVERITY_TEXT: Record<EventSeverity, string> = {
  [EventSeverity.INFO]: '提示',
  [EventSeverity.WARN]: '警告',
  [EventSeverity.ERROR]: '错误',
  [EventSeverity.CRITICAL]: '危急',
}
export const EVENT_SEVERITY_TAG: Record<EventSeverity, 'info' | 'warning' | 'danger'> = {
  [EventSeverity.INFO]: 'info',
  [EventSeverity.WARN]: 'warning',
  [EventSeverity.ERROR]: 'danger',
  [EventSeverity.CRITICAL]: 'danger',
}
