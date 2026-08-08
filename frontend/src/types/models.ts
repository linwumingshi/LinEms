/**
 * 与后端统一响应体/各业务 DTO 对应的 TypeScript 类型。
 * 字段命名与 Java（Jackson 默认驼峰）一一对应，见 Phase6 设计文档与对应 Controller 注释。
 */

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
  deviceId: number
  /** reported 属性快照（Map<属性标识, 值>） */
  reported: Record<string, unknown>
  /** desired 期望值快照 */
  desired: Record<string, unknown>
  /** 乐观锁版本；行不存在时为 null */
  version: number | null
}

export interface DesiredResult {
  deviceId: number
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
  createBy: number
}

export interface CommandView {
  commandId: string
  tenantId: number
  deviceId: number
  productKey: string
  command: string
  commandType: number
  params: Record<string, unknown>
  /** 状态机数值：见 command 域 state 字典 */
  state: number
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
  createBy: number
  createTime: string
}

// ---------------- 告警 Alarm ----------------

export interface AlarmRecord {
  alarmEventId: string
  tenantId: number
  deviceId: number
  productKey: string
  ruleId: number
  ruleCode: string
  /** 1 提示 2 一般 3 严重 4 危急 */
  level: number
  /** 1 属性 2 事件 3 策略 */
  type: number
  /** 0 触发中 1 已恢复 2 已确认 */
  status: number
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
  ruleId: number
  tenantId: number
  ruleCode: string
  ruleName: string
  productId: number | null
  deviceId: number | null
  triggerType: number
  condition: string
  severity: number
  silenceSeconds: number
  recovery: string | null
  status: number
  description: string | null
  createBy: number
  createTime: string
  updateTime: string
}

/** /ws/alarm 实时推送的告警事件（与 AlarmMessage 对应） */
export interface AlarmPush {
  alarmEventId: string
  tenantId: number
  deviceId: number
  productKey: string
  ruleId: number
  ruleCode: string
  level: number
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
  tenantId?: number
}

/** 登录响应（与 LoginResponse 对齐）：JWT + 基本信息 + 权限/角色标识 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  userId: number
  username: string
  realName: string | null
  tenantId: number
  enterpriseId: number | null
  permissions: string[]
  roles: string[]
}

// ---------------- 储能策略 EMS ----------------

export interface EmsStrategy {
  strategyId: number
  stationId: number
  strategyName: string
  strategyType: string
  config: string
  priority: number
  status: number
  version: number
  tenantId: number
  createTime: string
}

export interface EmsPlan {
  planId: number
  stationId: number
  strategyId: number
  planDate: string
  planType: number
  totalEnergy: number | null
  status: number
}

export interface EmsPlanPoint {
  time: string
  action: string
  powerKw: number
  socTarget: number
}

export interface EmsConstraint {
  constraintId: number
  tenantId: number
  stationId: number
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
  priceId: number
  tenantId: number
  stationId: number
  region: string
  /** DEEP/PEEK/PEAK/FLAT/VALLEY */
  priceType: string
  startTime: string
  endTime: string
  price: number
  validFrom: string
  validTo: string
  status: number
  createTime: string
}

// ---------------- 产品 Product ----------------

export interface Product {
  productId: number
  tenantId: number
  categoryId: number | null
  productKey: string
  productName: string
  deviceType: string
  authType: string
  protocol: string
  modelVersion: string | null
  description: string | null
  status: number
  createTime: string
  updateTime: string
  deleted: number
}

/** 产品保存请求（PUT 全量覆盖；modelVersion 由后端物模型发布维护，前端可不传） */
export type ProductSaveReq = Partial<Omit<Product, 'productId' | 'tenantId' | 'createTime' | 'updateTime' | 'deleted'>>

export interface ThingModelView {
  modelId: number
  productId: number
  version: string
  schemaJson: string
  status: number
  isCurrent: number
}

// ---------------- 设备 Device ----------------

export interface Device {
  deviceId: number
  tenantId: number
  enterpriseId: number | null
  stationId: number | null
  productKey: string
  deviceName: string
  deviceType: string
  parentId: number
  path: string
  level: number
  sort: number
  status: number
  firmwareVersion: string | null
  protocol: string
  brokerNode: string | null
  lastOnlineTime: string | null
  lastOfflineTime: string | null
  onlineSeconds: number
  mac: string | null
  ip: string | null
  children?: Device[]
  createTime: string
  updateTime: string
  deleted: number
}

export interface DeviceCreateReq {
  deviceName: string
  deviceType: string
  productKey: string
  parentId?: number
  stationId?: number
  enterpriseId?: number
  firmwareVersion?: string
  mac?: string
  ip?: string
  sort?: number
  status?: number
  protocol?: string
}

/** 更新仅改非空字段；productKey/deviceType/parentId/enterpriseId 不可改 */
export type DeviceUpdateReq = Partial<Pick<Device,
  'deviceName' | 'deviceType' | 'stationId' | 'status' | 'firmwareVersion' | 'mac' | 'ip' | 'sort'>>

export interface CredentialView {
  deviceId: number
  deviceName: string
  deviceSecret: string
  authStatus: number
}

// ---------------- RBAC 系统管理 ----------------

export interface SysUserVO {
  userId: number
  tenantId: number
  enterpriseId: number | null
  enterpriseName: string | null
  username: string
  realName: string
  phone: string | null
  email: string | null
  status: number
  lastLoginTime: string | null
  createTime: string
  roleIds: number[]
  roleNames: string[]
}

export interface SysUserSaveReq {
  username?: string
  realName?: string
  phone?: string
  email?: string
  enterpriseId?: number
  status?: number
  /** 创建必填(6~64)；更新留空=不改 */
  password?: string
  /** null=不改；数组=全量覆盖 */
  roleIds?: number[] | null
}

export interface SysRole {
  roleId: number
  tenantId: number
  roleCode: string
  roleName: string
  dataScope: number
  status: number
  createTime: string
  updateTime: string
}

export interface SysRoleSaveReq {
  roleCode?: string
  roleName?: string
  dataScope?: number
  status?: number
}

export interface SysPermission {
  permId: number
  parentId: number
  permCode: string
  permName: string
  permType: number
  resourceType: string | null
  path: string | null
  sort: number
  icon: string | null
  component: string | null
  visible: number
  status: number
  remark: string | null
  createTime: string
  updateTime: string
  children?: SysPermission[]
}

export interface SysPermissionSaveReq {
  parentId?: number
  permCode?: string
  permName?: string
  permType?: number
  resourceType?: string
  path?: string
  sort?: number
  icon?: string
  component?: string
  visible?: number
  status?: number
  remark?: string
}

export interface SysEnterprise {
  enterpriseId: number
  tenantId: number
  parentId: number
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
