/**
 * 与后端统一响应体/各业务 DTO 对应的 TypeScript 类型。
 * 字段命名与 Java（Jackson 默认驼峰）一一对应，见 Phase6 设计文档与对应 Controller 注释。
 */
import type {
  AlarmLevel, AlarmRecordStatus, CommandState, CredentialAuthStatus, DataScope,
  DeviceStatus, DeviceType, ElectricityPriceStatus, GridType, PermissionStatus,
  PlanStatus, PlanPointState, PriceType, ProductStatus, RevenuePeriodType, RoleStatus,
  StationStatus, StrategyStatus, StrategyType, ThingModelStatus, UserStatus,
} from '@/utils/enums'

/** 统一响应体 Result<T> */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  traceId: string
  timestamp: number
}

/** 分页结果 PageResult<T> */
export interface PageResult<T> {
  total: number
  pages: number
  current: number
  size: number
  records: T[]
}

// ---------------- 影子 Shadow ----------------

export interface ShadowView {
  deviceId: string
  /** reported 属性快照（Map<属性标识, 值>） */
  reported: Record<string, unknown>
  /** desired 期望值快照 */
  desired: Record<string, unknown>
  /** 乐观锁版本；行不存在时为 null */
  version: number | null
  /** 最近一次属性上报时间（ISO8601）；从未上报过为 undefined */
  lastReportedTime?: string
}

export interface DesiredResult {
  deviceId: string
  desired: Record<string, unknown>
  /** desired - reported 差异（将驱动设备同步） */
  delta: Record<string, unknown>
  version: number
}

// ---------------- 指令 Command ----------------

/** 创建指令请求体 */
export interface CreateCommandPayload {
  /** 幂等键（可选），服务端缺省生成 */
  commandId?: string
  productKey: string
  deviceName: string
  /** 物模型服务标识，如 setPower / startCharge */
  command: string
  params: Record<string, unknown>
  /** 1 读取 2 控制，默认 2 */
  commandType: number
  /** 指令超时（毫秒），默认 15000 */
  timeoutMs: number
  /** 最大重试，默认 3 */
  maxRetry: number
  /** 发起人（人工），默认 0=系统 */
  createBy: string
}

export interface CommandView {
  commandId: string
  tenantId: string
  deviceId: string
  productKey: string
  command: string
  commandType: number
  params: Record<string, unknown>
  /** 状态机数值：见 CommandState 枚举（0已创建~6超时） */
  state: CommandState
  /** CREATED/SENT/DEVICE_RECEIVED/EXECUTING/SUCCESS/FAILED/TIMEOUT */
  stateName: string
  retryCount: number
  maxRetry: number
  timeoutMs: number
  sentTime: string | null
  receivedTime: string | null
  executingTime: string | null
  finishTime: string | null
  result: Record<string, unknown>
  errorCode: string | null
  errorMsg: string | null
  createBy: string
  createTime: string
}

// ---------------- 告警 Alarm ----------------

export interface AlarmRecord {
  alarmEventId: string
  tenantId: string
  deviceId: string
  productKey: string
  ruleId: string
  ruleCode: string
  /** 告警级别（AlarmLevel：1提示 2一般 3严重 4危急） */
  level: AlarmLevel
  /** 1 属性 2 事件 3 策略 */
  type: number
  /** 记录状态（AlarmRecordStatus：0触发中 1已恢复 2已确认） */
  status: AlarmRecordStatus
  /** ACTIVE / RECOVERED / ACKED */
  statusName: string
  message: string
  ext: Record<string, unknown>
  triggeredTime: string
  recoveredTime: string | null
  ackedBy: string | null
  ackTime: string | null
}

export interface AlarmRule {
  ruleId: string
  tenantId: string
  ruleCode: string
  ruleName: string
  productId: string | null
  deviceId: string | null
  triggerType: number
  condition: string
  /** 规则级别（AlarmLevel：1提示 2一般 3严重 4危急） */
  severity: AlarmLevel
  silenceSeconds: number
  recovery: string | null
  status: number
  description: string | null
  createBy: string
  createTime: string
  updateTime: string
}

/** 告警规则新增/修改请求体（对齐后端 AlarmRuleSaveReq） */
export interface AlarmRuleSaveReq {
  /** 租户（缺省 1，单租户可省略） */
  tenantId?: number
  /** 规则编码，租户内唯一，编辑时不可改 */
  ruleCode: string
  ruleName: string
  productId?: number | null
  deviceId?: number | null
  /** 1属性比较 2事件 */
  triggerType: number
  /** 触发条件 JSON（{metric,op,value,windowSec} / {event}） */
  condition: string
  severity?: number
  silenceSeconds?: number
  /** 恢复条件 JSON（可空） */
  recovery?: string | null
  /** 0停用 1启用 */
  status?: number
  description?: string | null
}

  /** /ws/alarm 实时推送的告警事件（与 AlarmMessage 对应） */
export interface AlarmPush {
  alarmEventId: string
  tenantId: string
  deviceId: string
  productKey: string
  ruleId: string
  ruleCode: string
  level: AlarmLevel
  type: number
  /** ACTIVE（触发）/ RECOVERED（恢复） */
  status: 'ACTIVE' | 'RECOVERED'
  message: string
  ext: Record<string, unknown>
  /** 事件时间（毫秒） */
  ts: number
}

// ---------------- 认证 Auth ----------------

/** 登录请求体（与 LoginRequest 对齐；tenantId 缺省 1） */
export interface LoginRequest {
  username: string
  password: string
  tenantId?: string
}

/** 登录响应（与 LoginResponse 对齐）：JWT + 基本信息 + 权限/角色标识 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  userId: string
  username: string
  realName: string | null
  tenantId: string
  enterpriseId: string | null
  permissions: string[]
  roles: string[]
}

// ---------------- 储能策略 EMS ----------------

export interface EmsStrategy {
  strategyId: string
  stationId: string
  strategyName: string
  /** 策略类型（StrategyType：PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME） */
  strategyType: StrategyType
  config: string
  priority: number
  /** 策略状态（StrategyStatus：0草稿 1启用 2停用） */
  status: StrategyStatus
  version: number
  tenantId: string
  createTime: string
}

export interface EmsPlan {
  planId: string
  stationId: string
  strategyId: string
  planDate: string
  planType: number
  totalEnergy: number | null
  /** 计划状态（PlanStatus：0待执行 1执行中 2完成 3已取消 4失败） */
  status: PlanStatus
}

export interface EmsPlanPoint {
  time: string
  action: string
  powerKw: number
  socTarget: number
}

export interface EmsExecutionRecord {
  execId: string
  planId: string
  commandId: string
  /** 计划点时刻 HH:mm */
  planTime: string
  action: string
  /** 计划点执行状态（PlanPointState：0待下发 1已下发 2成功 3失败 4超时） */
  state: PlanPointState
  params: string | null
  result: string | null
  executeTime: string
}

export interface EmsConstraint {
  constraintId: string
  tenantId: string
  stationId: string
  socMin: number | null
  socMax: number | null
  chargePowerMax: number | null
  dischargePowerMax: number | null
  tempMax: number | null
  voltageMax: number | null
  currentMax: number | null
  safetyEnvelope: string | null
  status: number
  createTime: string
  updateTime: string
}

export interface EmsElectricityPrice {
  priceId: string
  tenantId: string
  stationId: string
  region: string
  /** 电价档位（PriceType：DEEP/VALLEY/FLAT/PEAK/PEEK） */
  priceType: PriceType
  startTime: string
  endTime: string
  price: number
  validFrom: string
  validTo: string
  /** 档案状态（ElectricityPriceStatus：0停用 1启用） */
  status: ElectricityPriceStatus
  createTime: string
}

// ---------------- 收益核算 Revenue (P1-1) ----------------

export interface RevenueSummary {
  stationId: string
  /** 统计周期（RevenuePeriodType：DAY/MONTH/YEAR） */
  periodType: RevenuePeriodType
  startDate: string
  endDate: string
  daysCount: number
  chargeEnergy: number
  dischargeEnergy: number
  totalEnergy: number
  arbitrageRevenue: number
  /** P1-2 前恒 0 */
  demandSavings: number
  totalRevenue: number
  investmentAmount: number | null
  paybackYears: number | null
  hasInvestment: boolean
}

export interface RevenueTrendPoint {
  /** 月视图 MM-dd、年视图 yyyy-MM */
  label: string
  chargeEnergy: number
  dischargeEnergy: number
  revenue: number
}

export interface RevenueDetailRow {
  time: string
  action: string
  energyKwh: number
  price: number
  revenue: number
  /** RUN_MODE/PLAN */
  source: string
}

export interface EmsStationMeta {
  stationMetaId: string
  stationId: string
  investmentAmount: number | null
  installDate: string | null
  createTime?: string
  updateTime?: string
}

// ---------------- 需量管理（P1-2） ----------------

export interface EmsDemandConfig {
  demandConfigId?: string
  tenantId?: string
  stationId: string
  /** 需量限值 kW（>0 启用检测） */
  demandLimitKw: number | null
  /** 需量费率 ¥/kW·月 */
  demandRate: number | null
  createTime?: string
  updateTime?: string
}

export interface EmsDemandRecord {
  demandRecordId?: string
  tenantId?: string
  stationId: string
  /** 槽位起点（yyyy-MM-ddTHH:mm:ss） */
  windowStart: string
  /** 槽位终点 */
  windowEnd: string
  /** 槽位实际需量（15min 平均功率 kW） */
  demandKw: number
  /** 限值快照 kW */
  limitKw: number | null
  overLimit: boolean
  /** 削峰放电功率 kW */
  shavedKw: number
  /** NONE/SHED/SHED_FAILED/ALARM_ONLY */
  action: string
  createTime?: string
}

export interface DemandSavingsView {
  stationId: string
  /** 统计周期（RevenuePeriodType：DAY/MONTH/YEAR） */
  periodType: RevenuePeriodType
  startDate: string
  endDate: string
  /** 实际最大需量 kW */
  actualMaxKw: number
  /** 未削峰最大需量 kW */
  unshavedMaxKw: number
  /** 节省金额 元 */
  savings: number
}

// ---------------- 电站 Station ----------------

/** 电站资产（iot_station；stationId 为 Long，序列化为字符串，同雪花约定） */
export interface Station {
  stationId: string
  tenantId?: string
  enterpriseId?: string | null
  stationCode?: string | null
  stationName: string
  address?: string | null
  longitude?: number | null
  latitude?: number | null
  installCapacity?: number | null
  pcsCapacity?: number | null
  batteryCapacity?: number | null
  /** 电网类型（GridType：工商业/园区/电网侧） */
  gridType?: GridType | null
  /** 电站状态（StationStatus：0停运 1运行） */
  status?: StationStatus
  createTime?: string
  updateTime?: string
}

/** 单位创建/更新请求（后端 SysEnterpriseSaveReq；parentId 空=顶级） */
export interface SysEnterpriseSaveReq {
  enterpriseCode: string
  enterpriseName: string
  parentId?: string
  sort?: number
  status?: number
}

/** 电站创建/更新请求（后端 StationSaveReq；capacity 均为可空数值） */
export interface StationSaveReq {
  stationCode: string
  stationName: string
  enterpriseId: string
  address?: string
  longitude?: number | null
  latitude?: number | null
  installCapacity?: number | null
  pcsCapacity?: number | null
  batteryCapacity?: number | null
  /** 电网类型（GridType：工商业/园区/电网侧） */
  gridType?: GridType
  /** 电站状态（StationStatus：0停运 1运行） */
  status?: StationStatus
}

// ---------------- 产品 Product ----------------

export interface Product {
  productId: string
  tenantId: string
  categoryId: string | null
  productKey: string
  productName: string
  /** 设备类型（DeviceType：ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER） */
  deviceType: DeviceType
  authType: string
  protocol: string
  modelVersion: string | null
  description: string | null
  /** 产品状态（ProductStatus：0禁用 1启用） */
  status: ProductStatus
  createTime: string
  updateTime: string
  deleted: number
}

/** 产品保存请求（PUT 全量覆盖；modelVersion 由后端物模型发布维护，前端可不传） */
export type ProductSaveReq = Partial<Omit<Product, 'productId' | 'tenantId' | 'createTime' | 'updateTime' | 'deleted'>>

export interface ThingModelView {
  modelId: string
  productId: string
  version: string
  schemaJson: string
  /** 物模型状态（ThingModelStatus：0草稿 1已发布 2已废弃） */
  status: ThingModelStatus
  isCurrent: number
}

// ---------------- 时序历史 Tsdb ----------------

/** 物模型数据类型（TSL 标准 dataType，字符串枚举；specs 携带该类型的扩展字段如 min/max/length/enumValues/elementType） */
export type TsDataType = 'int' | 'long' | 'float' | 'double' | 'text' | 'bool' | 'date' | 'enum' | 'struct' | 'array'

/** 物模型属性（TSL properties 条目） */
export interface TsProperty {
  identifier: string
  name: string
  dataType: TsDataType
  unit?: string
  /** r=只读 w=只写 rw=读写 */
  accessMode?: 'r' | 'w' | 'rw'
  /** 数据类型扩展规格（min/max/length/unit/step/enumValues/elementType/structFields 等） */
  specs?: Record<string, unknown>
  required?: boolean
  desc?: string
}

/** 物模型入参/出参/事件输出参数（通用 Param；dataType 字符串 + specs） */
export interface TsParam {
  identifier: string
  name: string
  dataType: TsDataType
  unit?: string
  required?: boolean
  specs?: Record<string, unknown>
  desc?: string
}

/** 物模型服务/命令（TSL services 条目；callType ASYNC/SYNC） */
export interface TsService {
  identifier: string
  name: string
  callType?: 'SYNC' | 'ASYNC'
  /** 输入参数列表 */
  input?: TsParam[]
  /** 输出参数列表 */
  output?: TsParam[]
  desc?: string
}

/** 物模型事件（TSL events 条目；type INFO/WARN/ERROR） */
export interface TsEvent {
  identifier: string
  name: string
  type?: 'INFO' | 'WARN' | 'ERROR'
  /** 事件输出参数 */
  data?: TsParam[]
  desc?: string
}

/** 物模型 schema_json 顶层结构 */
export interface ThingModelSchema {
  properties: TsProperty[]
  services: TsService[]
  events: TsEvent[]
}

/** TDengine 属性历史单行（某属性该行为 NULL 时 values 省略该键） */
export interface PropertyHistoryRecord {
  /** epoch 毫秒 */
  ts: number
  values: Record<string, number | string | null>
}

/** TDengine 属性历史分页视图（ts/total 均为数字） */
export interface PropertyHistoryView {
  deviceId: string
  productKey: string
  total: number
  records: PropertyHistoryRecord[]
}

// ---------------- 设备 Device ----------------

export interface Device {
  deviceId: string
  tenantId: string
  enterpriseId: string | null
  stationId: string | null
  productKey: string
  deviceName: string
  /** 设备类型（DeviceType：ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER） */
  deviceType: DeviceType
  parentId: string
  path: string
  level: number
  sort: number
  /** 设备生命周期状态（DeviceStatus：0未注册~5封禁） */
  status: DeviceStatus
  firmwareVersion: string | null
  protocol: string
  brokerNode: string | null
  lastOnlineTime: string | null
  lastOfflineTime: string | null
  onlineSeconds: string
  mac: string | null
  ip: string | null
  children?: Device[]
  createTime: string
  updateTime: string
  deleted: number
}

export interface DeviceCreateReq {
  deviceName: string
  /** 设备类型（DeviceType：ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER） */
  deviceType: DeviceType
  productKey: string
  parentId?: string
  stationId?: string
  enterpriseId?: string
  firmwareVersion?: string
  mac?: string
  ip?: string
  sort?: number
  /** 设备状态（DeviceStatus：0未注册~5封禁） */
  status?: DeviceStatus
  protocol?: string
}

/** 更新仅改非空字段；productKey/deviceType/parentId/enterpriseId 不可改 */
export type DeviceUpdateReq = Partial<Pick<Device,
  'deviceName' | 'deviceType' | 'stationId' | 'status' | 'firmwareVersion' | 'mac' | 'ip' | 'sort'>>

export interface CredentialView {
  deviceId: string
  deviceName: string
  deviceSecret: string
  /** 凭据状态（CredentialAuthStatus：1正常 2吊销） */
  authStatus: CredentialAuthStatus
}

// ---------------- RBAC 系统管理 ----------------

export interface SysUserVO {
  userId: string
  tenantId: string
  enterpriseId: string | null
  enterpriseName: string | null
  username: string
  realName: string
  phone: string | null
  email: string | null
  /** 用户状态（UserStatus：0禁用 1启用 2锁定） */
  status: UserStatus
  lastLoginTime: string | null
  createTime: string
  roleIds: string[]
  roleNames: string[]
}

export interface SysUserSaveReq {
  username?: string
  realName?: string
  phone?: string
  email?: string
  enterpriseId?: string
  /** 用户状态（UserStatus：0禁用 1启用 2锁定） */
  status?: UserStatus
  /** 创建必填(6~64)；更新留空=不改 */
  password?: string
  /** null=不改；数组=全量覆盖 */
  roleIds?: string[] | null
}

export interface SysRole {
  roleId: string
  tenantId: string
  roleCode: string
  roleName: string
  /** 数据范围（DataScope：1本人 2本企业 3本租户 4全部） */
  dataScope: DataScope
  /** 角色状态（RoleStatus：0禁用 1启用） */
  status: RoleStatus
  createTime: string
  updateTime: string
}

export interface SysRoleSaveReq {
  roleCode?: string
  roleName?: string
  /** 数据范围（DataScope：1本人 2本企业 3本租户 4全部） */
  dataScope?: DataScope
  /** 角色状态（RoleStatus：0禁用 1启用） */
  status?: RoleStatus
}

export interface SysPermission {
  permId: string
  parentId: string
  permCode: string
  permName: string
  permType: number
  resourceType: string | null
  path: string | null
  sort: number
  icon: string | null
  component: string | null
  visible: number
  /** 权限状态（PermissionStatus：0正常 1停用） */
  status: PermissionStatus
  remark: string | null
  createTime: string
  updateTime: string
  children?: SysPermission[]
}

export interface SysPermissionSaveReq {
  parentId?: string
  permCode?: string
  permName?: string
  permType?: number
  resourceType?: string
  path?: string
  sort?: number
  icon?: string
  component?: string
  visible?: number
  /** 权限状态（PermissionStatus：0正常 1停用） */
  status?: PermissionStatus
  remark?: string
}

export interface SysEnterprise {
  enterpriseId: string
  tenantId: string
  parentId: string
  path: string
  level: number
  enterpriseCode: string
  enterpriseName: string
  sort: number
  status: number
  children?: SysEnterprise[]
  createTime: string
  updateTime: string
  deleted: number
}

// ================================================================
// 场景联动与规则编排（Phase 11，对应后端 energy-rule 模块）
// ================================================================

/** 设备引用（Trigger/Condition/Action 共用；deviceName 为空=产品级，仅 Trigger 支持） */
export interface RuleDevice {
  productKey: string
  deviceName?: string | null
}

/** 比较操作符（与后端 DslValidator 一致） */
export type RuleOp = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NEQ'

/** 触发器（triggers[]，多触发器 OR） */
export interface RuleTrigger {
  type: 'PROPERTY' | 'TIMER' | 'LIFECYCLE' | 'ALARM' | 'MANUAL'
  device?: RuleDevice | null
  /** PROPERTY 属性标识，如 cellTemp / soc */
  property?: string | null
  /** PROPERTY 比较操作符 */
  op?: RuleOp | null
  /** PROPERTY 阈值 */
  value?: string | number | null
  /** TIMER cron（6 位：秒 分 时 日 月 周） */
  cron?: string | null
  /** LIFECYCLE 事件 ONLINE/OFFLINE */
  event?: 'ONLINE' | 'OFFLINE' | null
  /** ALARM 告警码（可空） */
  alarmCode?: string | null
  /** ALARM 级别 1提示 2一般 3严重 4危急（可空） */
  level?: number | null
  /** ALARM 状态 ACTIVE/RECOVER */
  state?: 'ACTIVE' | 'RECOVER' | null
}

/** 执行条件（conditions[]，多条件 AND；空=恒真） */
export interface RuleCondition {
  type: 'DEVICE_STATUS' | 'TIME_RANGE' | 'PROPERTY'
  device?: RuleDevice | null
  /** DEVICE_STATUS 在线状态 */
  status?: 'ONLINE' | 'OFFLINE' | null
  /** TIME_RANGE 起点 HH:mm */
  start?: string | null
  /** TIME_RANGE 终点 HH:mm */
  end?: string | null
  /** PROPERTY 属性标识 */
  property?: string | null
  /** PROPERTY 比较操作符 */
  op?: RuleOp | null
  /** PROPERTY 阈值 */
  value?: string | number | null
}

/** 执行动作（actions[]，多动作独立执行） */
export interface RuleAction {
  type: 'DEVICE_COMMAND' | 'ALARM' | 'NOTIFY' | 'RULE'
  device?: RuleDevice | null
  /** DEVICE_COMMAND 物模型服务标识 */
  command?: string | null
  /** DEVICE_COMMAND 参数 */
  params?: Record<string, unknown> | null
  /** DEVICE_COMMAND 超时（ms） */
  timeoutMs?: number | null
  /** DEVICE_COMMAND 最大重试 */
  maxRetry?: number | null
  /** ALARM 场景告警编码 */
  ruleCode?: string | null
  /** ALARM 级别 1提示 2一般 3严重 4危急 */
  severity?: number | null
  /** ALARM 告警内容 */
  message?: string | null
  /** NOTIFY 渠道（当前仅 WEBHOOK） */
  channel?: string | null
  /** NOTIFY webhook 地址 */
  url?: string | null
  /** NOTIFY 请求头（模板变量可渲染） */
  headers?: Record<string, string> | null
  /** NOTIFY 消息模板（${property.xxx} 渲染；兼容旧版直发 webhook） */
  template?: string | null
  /** NOTIFY 通知配置编码（消息通知模块 energy-notify，优先于 url 直发） */
  notifyConfigCode?: string | null
  /** NOTIFY 通知模板编码（与配置渠道一致） */
  notifyTemplateCode?: string | null
  /** NOTIFY 直接内容（非空时跳过模板渲染） */
  notifyContent?: string | null
  /** RULE 嵌套目标规则 ID */
  ruleId?: number | null
}

/** 恢复配置（可选；条件从满足→不满足时执行恢复动作，不受防抖限制） */
export interface RuleRecovery {
  property: string
  op: RuleOp
  value: string | number
  actions: RuleAction[]
}

/** TCA DSL 根配置（对应后端 RuleConfig） */
export interface RuleConfig {
  dslVersion?: number
  name?: string | null
  triggers: RuleTrigger[]
  conditions: RuleCondition[]
  actions: RuleAction[]
  recovery?: RuleRecovery | null
}

/** 规则视图（详情/分页返回，对应后端 RuleView） */
export interface RuleView {
  ruleId: number
  tenantId: string
  ruleCode: string
  ruleName: string
  description: string | null
  dslVersion: number
  dsl: RuleConfig
  debounceSeconds: number
  priority: number
  /** 0停用 1启用 */
  enabled: number
  /** 乐观锁版本 */
  version: number
  createBy: string
  createTime: string
  updateTime: string
}

/** 规则保存请求（创建/更新，对应后端 SaveRuleRequest；更新时 version 必填） */
export interface RuleSaveReq {
  ruleCode: string
  ruleName: string
  description?: string | null
  dsl: RuleConfig
  debounceSeconds?: number
  priority?: number
  enabled?: boolean
  version?: number
}

/** 规则执行日志视图（对应后端 RuleLogView） */
export interface RuleLogView {
  logId: string
  ruleId: string
  ruleCode: string
  tenantId: string
  triggerType: 'PROPERTY' | 'TIMER' | 'LIFECYCLE' | 'ALARM' | 'MANUAL' | 'RULE'
  deviceId: string | null
  /** 1=条件满足执行 0=触发未过条件 */
  matched: number
  /** 每个动作的执行结果 JSON */
  actionResult: string | null
  costMs: number
  traceId: string | null
  createTime: string
}

// ---------------- 消息通知（Phase 11 扩展） ----------------

/** 通知配置（iot_notify_config；channel_config 为 JSON 字符串） */
export interface NotifyConfig {
  configId: string
  tenantId: string
  configCode: string
  configName: string
  /** WEBHOOK/WECOM/DINGTALK/EMAIL */
  channel: string
  /** 渠道配置 JSON：WEBHOOK={url,headers} WECOM={webhook} DINGTALK={webhook,secret} EMAIL={host,port,username,password,from,ssl,to} */
  channelConfig: string
  status: number
  description: string | null
  createBy: string
  createTime: string
  updateTime: string
}

/** 通知配置保存请求 */
export interface NotifyConfigSaveReq {
  configCode: string
  configName: string
  channel: string
  channelConfig: string
  status?: number
  description?: string | null
}

/** 通知模板（iot_notify_template；content_template 支持 ${xxx} 占位符） */
export interface NotifyTemplate {
  templateId: string
  tenantId: string
  templateCode: string
  templateName: string
  /** ALARM/SCENE/DEVICE_EVENT/SYSTEM */
  messageType: string
  channel: string
  titleTemplate: string | null
  contentTemplate: string
  variables: string | null
  status: number
  description: string | null
  createBy: string
  createTime: string
  updateTime: string
}

/** 通知模板保存请求 */
export interface NotifyTemplateSaveReq {
  templateCode: string
  templateName: string
  messageType: string
  channel: string
  titleTemplate?: string | null
  contentTemplate: string
  variables?: string | null
  status?: number
  description?: string | null
}

/** 通知发送请求 */
export interface NotifySendRequest {
  configCode: string
  templateCode?: string
  content?: string
  title?: string
  context?: Record<string, unknown>
}

/** 渠道选项 */
export interface NotifyChannelOption {
  code: string
  label: string
  supported: string
}

// ---------------- OTA 固件升级（Phase12） ----------------

/** 升级包 */
export interface OtaPackage {
  packageId: string
  productKey: string
  version: string
  module: string
  packageType: number
  baseVersion: string | null
  fileName: string
  fileSize: number
  md5: string
  sha256: string
  signature: string | null
  sourceVersions: string | null
  description: string | null
  status: number
  createBy: string
  createTime: string
  updateTime: string
}

/** 设备投影（创建任务设备选择器用，对齐后端 DeviceView） */
export interface DeviceView {
  deviceId: string
  tenantId: string
  enterpriseId: string
  stationId: string
  productKey: string
  deviceName: string
  deviceType: string
  status: number
  firmwareVersion: string | null
}

/** 升级包上传请求 */
export interface OtaPackageSaveReq {
  productKey: string
  version: string
  module?: string
  packageType?: number
  baseVersion?: string
  sourceVersions?: string
  description?: string
}

/** OTA 批次任务 */
export interface OtaTask {
  taskId: string
  packageId: string
  taskName: string
  taskType: number
  downloadPolicy: number
  grayRatio: number | null
  deviceCount: number
  successCount: number
  failCount: number
  status: number
  retryTimes: number
  retryIntervalMin: number
  downloadTimeoutMin: number
  upgradeTimeoutMin: number
  autoPauseOnFail: number
  scheduleTime: string | null
  createBy: string
  createTime: string
  updateTime: string
}

/** 创建任务请求 */
export interface OtaTaskCreateReq {
  packageId: string
  taskName?: string
  taskType?: number
  deviceIds?: string[]
  downloadPolicy?: number
  grayRatio?: number
  retryTimes?: number
  retryIntervalMin?: number
  downloadTimeoutMin?: number
  upgradeTimeoutMin?: number
  autoPauseOnFail?: number
  scheduleTime?: string | null
}

/** 任务-设备明细 */
export interface OtaTaskDevice {
  taskId: string
  deviceId: string
  tenantId: string
  state: number
  progress: number
  versionBefore: string | null
  versionAfter: string | null
  failCode: string | null
  failMsg: string | null
  retryCount: number
  retryAt: string | null
  startTime: string | null
  finishTime: string | null
}

/** 任务统计 */
export interface OtaTaskStatistics {
  taskId: string
  deviceCount: number
  successCount: number
  failCount: number
  successRate: number
  status: number
}
