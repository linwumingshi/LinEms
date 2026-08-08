# 管理后台页面（产品 / 设备 / RBAC）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐产品管理、设备管理、RBAC（用户/角色/菜单权限）5 个管理页面，风格与现有 EnergyX 仪表设计语言一致，侧边栏按权限过滤。

**Architecture:** 纯前端实现，不动后端。新增 3 个 API 模块 + 8 个类型 + 2 个纯函数工具（`hasPermi`/dicts），新建 5 个视图，扩展路由与侧边栏。复用现有 `ex-page`/`ex-card`/`ex-readout-band` 结构件与 `http.ts` 拦截器。

**Tech Stack:** Vue3 `<script setup>` + TS + Pinia + Element Plus + Vite（vitest 单测，`npm run build` 走 vue-tsc 类型检查）。

## Global Constraints

- 分页参数**两套**：product/device 用 `pageNum`/`pageSize`（默认 20）；system 的 user/role 用 `current`/`size`（默认 10）。
- 网关 StripPrefix=1，前端 URL 一律 `/api/xxx`；请求体/查询**不带 tenantId**（网关透传）。
- 权限过滤：product/device 菜单**恒显**（后端无权限码）；system 三页按 `system:user:list`/`system:role:list`/`system:perm:list` 过滤；超管 `*:*:*` 全显。
- `deviceName` 禁 `_` 与 `&`；`username` 匹配 `^[a-zA-Z0-9_.-]+$`；`roleCode` 匹配 `^[A-Za-z][A-Za-z0-9_]*$`。
- 用户密码：创建必填（6~64），编辑留空=不改；`roleIds` null=不改、数组=全量覆盖。
- 角色/权限分配 body 是 **`List<Long>` 裸数组**，全量覆盖。
- 设备凭据：`GET credential` 脱敏、`POST credential/regenerate` 明文一次。
- 破坏性动作（删除/重生成凭据/重置密码/启停）一律 `ElMessageBox.confirm`。
- 时间展示统一 `toLocal()`（`@/utils/alarmFormat`）。
- 每任务结束 `npm run build`（vue-tsc + vite build）通过才可提交；纯函数任务先写失败测试。

设计文档：`docs/superpowers/specs/2026-08-08-admin-pages-design.md`

---

### Task 1: `hasPermi` 权限工具 + 单测

**Files:**
- Create: `frontend/src/utils/permission.ts`
- Test: `frontend/src/utils/__tests__/permission.spec.ts`

**Interfaces:**
- Produces: `hasPermi(perms: string[] | undefined, required: string | string[]): boolean` —— perms 含 `*:*:*` 恒真；required 为空恒真；多 required 任一命中即真；无 perms 恒假。

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/utils/__tests__/permission.spec.ts
import { describe, expect, it } from 'vitest'
import { hasPermi } from '@/utils/permission'

describe('hasPermi', () => {
  it('无 required 恒真', () => {
    expect(hasPermi(['x'], '')).toBe(true)
    expect(hasPermi(undefined, [])).toBe(true)
  })

  it('无 perms 恒假', () => {
    expect(hasPermi(undefined, 'system:user:list')).toBe(false)
    expect(hasPermi([], 'system:user:list')).toBe(false)
  })

  it('超管 *:*:* 恒真', () => {
    expect(hasPermi(['*:*:*'], 'system:user:list')).toBe(true)
    expect(hasPermi(['system:role:list', '*:*:*'], 'system:perm:list')).toBe(true)
  })

  it('单权限精确匹配', () => {
    expect(hasPermi(['system:user:list'], 'system:user:list')).toBe(true)
    expect(hasPermi(['system:user:list'], 'system:user:add')).toBe(false)
  })

  it('多权限任一命中即真', () => {
    expect(hasPermi(['system:role:list'], ['system:user:list', 'system:role:list'])).toBe(true)
    expect(hasPermi(['system:role:list'], ['system:user:list', 'system:user:add'])).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- -- src/utils/__tests__/permission.spec.ts`
Expected: FAIL —— `Cannot find module '@/utils/permission'`

- [ ] **Step 3: Write minimal implementation**

```ts
// frontend/src/utils/permission.ts
/** 权限判定：perms 含超管 *:*:* 恒真；required 空恒真；多 required 任一命中即真 */
export function hasPermi(perms: string[] | undefined, required: string | string[]): boolean {
  if (required == null || required === '' || (Array.isArray(required) && required.length === 0)) return true
  if (!perms) return false
  if (perms.includes('*:*:*')) return true
  const need = Array.isArray(required) ? required : [required]
  return need.some((p) => perms.includes(p))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm run test -- -- src/utils/__tests__/permission.spec.ts`
Expected: PASS（5 用例全绿）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/permission.ts frontend/src/utils/__tests__/permission.spec.ts
git commit -m "feat(web): hasPermi 权限判定工具"
```

---

### Task 2: 状态字典 `dicts.ts` + 单测

**Files:**
- Create: `frontend/src/utils/dicts.ts`
- Test: `frontend/src/utils/__tests__/dicts.spec.ts`

**Interfaces:**
- Produces:
  - `productStatusText(s): string`（0 禁用 / 1 启用）
  - `deviceStatusText(s): string`（0未注册 1未激活 2离线 3在线 4禁用 5封禁）
  - `deviceStatusTag(s): 'info'|'primary'|'success'|'danger'|''`（0→'' 1→'primary' 2→'info' 3→'success' 4→'danger' 5→'danger'）
  - `userStatusText(s): string`（0 禁用 / 1 启用 / 2 锁定）
  - `userStatusTag(s): 'danger'|'success'|'info'`
  - `roleStatusText(s): string`（0 停用 / 1 启用）
  - `roleStatusTag(s): 'danger'|'success'`
  - `dataScopeText(s): string`（1 本人 / 2 本企业 / 3 本租户 / 4 全部）
  - `permTypeText(s): string`（1 菜单 / 2 按钮 / 3 数据）
  - `thingModelStatusText(s): string`（0 草稿 / 1 已发布 / 2 已废弃）
  - `authStatusText(s): string`（1 正常 / 2 吊销）
  - `deviceTypeOptions: string[]`（`['ENERGY_CABINET','BATTERY_CLUSTER','PCS','BMS','EMS','EDGE_GW']`）
  - 未知值一律 `未知(N)`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/utils/__tests__/dicts.spec.ts
import { describe, expect, it } from 'vitest'
import {
  authStatusText, dataScopeText, deviceStatusTag, deviceStatusText,
  deviceTypeOptions, permTypeText, productStatusText, roleStatusTag,
  roleStatusText, thingModelStatusText, userStatusTag, userStatusText,
} from '@/utils/dicts'

describe('dicts', () => {
  it('产品状态', () => {
    expect(productStatusText(0)).toBe('禁用')
    expect(productStatusText(1)).toBe('启用')
    expect(productStatusText(9)).toBe('未知(9)')
  })

  it('设备状态字典', () => {
    expect(deviceStatusText(0)).toBe('未注册')
    expect(deviceStatusText(3)).toBe('在线')
    expect(deviceStatusText(5)).toBe('封禁')
    expect(deviceStatusTag(3)).toBe('success')
    expect(deviceStatusTag(4)).toBe('danger')
    expect(deviceStatusTag(99)).toBe('info')
  })

  it('用户/角色状态', () => {
    expect(userStatusText(1)).toBe('启用')
    expect(userStatusTag(0)).toBe('danger')
    expect(roleStatusText(1)).toBe('启用')
    expect(roleStatusTag(0)).toBe('danger')
  })

  it('数据范围 / 权限类型 / 物模型状态 / 凭据状态', () => {
    expect(dataScopeText(3)).toBe('本租户')
    expect(dataScopeText(4)).toBe('全部')
    expect(permTypeText(2)).toBe('按钮')
    expect(thingModelStatusText(1)).toBe('已发布')
    expect(authStatusText(2)).toBe('吊销')
  })

  it('设备类型枚举', () => {
    expect(deviceTypeOptions).toContain('PCS')
    expect(deviceTypeOptions).toContain('BATTERY_CLUSTER')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- -- src/utils/__tests__/dicts.spec.ts`
Expected: FAIL —— `Cannot find module '@/utils/dicts'`

- [ ] **Step 3: Write minimal implementation**

```ts
// frontend/src/utils/dicts.ts
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm run test -- -- src/utils/__tests__/dicts.spec.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/dicts.ts frontend/src/utils/__tests__/dicts.spec.ts
git commit -m "feat(web): 状态/类型字典 dicts"
```

---

### Task 3: 类型定义 + 三个 API 模块

**Files:**
- Modify: `frontend/src/types/models.ts`（在文件末尾追加，不删既有类型）
- Create: `frontend/src/api/product.ts`
- Create: `frontend/src/api/device.ts`
- Create: `frontend/src/api/system.ts`

**Interfaces:**
- Consumes: `http`（`@/api/http` 默认导出，`Promise` 已被拦截器解包为 `data`）、`PageResult<T>`（models.ts 已有）。
- Produces（后续 Task 4-8 全部依赖这些签名）:
  - 类型：`Product`、`ThingModelView`、`Device`、`CredentialView`、`SysUserVO`、`SysRole`、`SysPermission`、`SysEnterprise` + 请求体类型 `ProductSaveReq`/`DeviceCreateReq`/`DeviceUpdateReq`/`SysUserSaveReq`/`SysRoleSaveReq`/`SysPermissionSaveReq`
  - `productApi`：`page(params?)/detail(id)/create(body)/update(id,body)/remove(id)/thingModelGet(id)/thingModelSave(id,{version,schemaJson})`
  - `deviceApi`：`page(params?)/detail(id)/create(body)/update(id,body)/remove(id)/credential(id)/regenerateCredential(id)`
  - `userApi`：`page(params?)/create(body)/update(id,body)/remove(id)/switchStatus(id,status)/resetPassword(id,password)/roles(id)/assignRoles(id,roleIds)`
  - `roleApi`：`page(params?)/list()/create(body)/update(id,body)/remove(id)/switchStatus(id,status)/perms(id)/assignPerms(id,permIds)`
  - `permApi`：`tree()/create(body)/update(id,body)/remove(id)/switchStatus(id,status)`
  - `enterpriseApi`：`list()`

- [ ] **Step 1: 在 models.ts 末尾追加类型**

在 `frontend/src/types/models.ts` 文件末尾（`EmsElectricityPrice` 之后）追加：

```ts
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
```

- [ ] **Step 2: 创建 product API 模块**

```ts
// frontend/src/api/product.ts
import http from './http'
import type { PageResult, Product, ProductSaveReq, ThingModelView } from '@/types/models'

/** 产品 API（网关 /api/product/** StripPrefix=1 → energy-product；分页参数 pageNum/pageSize） */
export const productApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<Product>> {
    return http.get('/api/product/page', { params })
  },
  detail(productId: number): Promise<Product> {
    return http.get(`/api/product/${productId}`)
  },
  create(body: ProductSaveReq): Promise<number> {
    return http.post('/api/product', body)
  },
  update(productId: number, body: ProductSaveReq): Promise<void> {
    return http.put(`/api/product/${productId}`, body)
  },
  remove(productId: number): Promise<void> {
    return http.delete(`/api/product/${productId}`)
  },
  /** 物模型单版本视图；未发布后端返回业务错误（页面据此置空态） */
  thingModelGet(productId: number): Promise<ThingModelView> {
    return http.get(`/api/product/${productId}/thing-model`)
  },
  /** 发布/覆盖：同 version 覆盖并置当前，异 version 新增并切换当前 */
  thingModelSave(productId: number, body: { version: string; schemaJson: string }): Promise<ThingModelView> {
    return http.put(`/api/product/${productId}/thing-model`, body)
  },
}
```

- [ ] **Step 3: 创建设备 API 模块**

```ts
// frontend/src/api/device.ts
import http from './http'
import type { CredentialView, Device, DeviceCreateReq, DeviceUpdateReq, PageResult } from '@/types/models'

/** 设备 API（网关 /api/device/** StripPrefix=1 → energy-device；分页参数 pageNum/pageSize） */
export const deviceApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<Device>> {
    return http.get('/api/device/page', { params })
  },
  detail(deviceId: number): Promise<Device> {
    return http.get(`/api/device/${deviceId}`)
  },
  /** 创建返回雪花 deviceId（明文密钥仅此一次，随后需凭据接口/重生成查看） */
  create(body: DeviceCreateReq): Promise<number> {
    return http.post('/api/device', body)
  },
  update(deviceId: number, body: DeviceUpdateReq): Promise<void> {
    return http.put(`/api/device/${deviceId}`, body)
  },
  /** 逻辑删除整棵子树 + 吊销凭据 */
  remove(deviceId: number): Promise<void> {
    return http.delete(`/api/device/${deviceId}`)
  },
  /** 凭据查询：密钥脱敏（abcd****wxyz） */
  credential(deviceId: number): Promise<CredentialView> {
    return http.get(`/api/device/${deviceId}/credential`)
  },
  /** 重生成密钥：返回明文（仅本次） */
  regenerateCredential(deviceId: number): Promise<CredentialView> {
    return http.post(`/api/device/${deviceId}/credential/regenerate`)
  },
}
```

- [ ] **Step 4: 创建 system API 模块**

```ts
// frontend/src/api/system.ts
import http from './http'
import type {
  PageResult, SysEnterprise, SysPermission, SysPermissionSaveReq,
  SysRole, SysRoleSaveReq, SysUserSaveReq, SysUserVO,
} from '@/types/models'

/** RBAC API（网关 /api/system/** StripPrefix=1 → energy-system；分页参数 current/size） */

export const userApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<SysUserVO>> {
    return http.get('/api/system/user/page', { params })
  },
  create(body: SysUserSaveReq): Promise<number> {
    return http.post('/api/system/user', body)
  },
  update(userId: number, body: SysUserSaveReq): Promise<void> {
    return http.put(`/api/system/user/${userId}`, body)
  },
  remove(userId: number): Promise<void> {
    return http.delete(`/api/system/user/${userId}`)
  },
  switchStatus(userId: number, status: number): Promise<void> {
    return http.put(`/api/system/user/${userId}/status?status=${status}`)
  },
  resetPassword(userId: number, password: string): Promise<void> {
    return http.put(`/api/system/user/${userId}/password`, { password })
  },
  roles(userId: number): Promise<number[]> {
    return http.get(`/api/system/user/${userId}/roles`)
  },
  assignRoles(userId: number, roleIds: number[]): Promise<void> {
    return http.put(`/api/system/user/${userId}/roles`, roleIds)
  },
}

export const roleApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<SysRole>> {
    return http.get('/api/system/role/page', { params })
  },
  /** 全量角色列表（下拉用） */
  list(): Promise<SysRole[]> {
    return http.get('/api/system/role/list')
  },
  create(body: SysRoleSaveReq): Promise<number> {
    return http.post('/api/system/role', body)
  },
  update(roleId: number, body: SysRoleSaveReq): Promise<void> {
    return http.put(`/api/system/role/${roleId}`, body)
  },
  remove(roleId: number): Promise<void> {
    return http.delete(`/api/system/role/${roleId}`)
  },
  switchStatus(roleId: number, status: number): Promise<void> {
    return http.put(`/api/system/role/${roleId}/status?status=${status}`)
  },
  perms(roleId: number): Promise<number[]> {
    return http.get(`/api/system/role/${roleId}/perms`)
  },
  /** 全量覆盖：body 为裸 List<Long>（含半选父节点由前端拼好传入） */
  assignPerms(roleId: number, permIds: number[]): Promise<void> {
    return http.put(`/api/system/role/${roleId}/perms`, permIds)
  },
}

export const permApi = {
  tree(): Promise<SysPermission[]> {
    return http.get('/api/system/perm/tree')
  },
  create(body: SysPermissionSaveReq): Promise<number> {
    return http.post('/api/system/perm', body)
  },
  update(permId: number, body: SysPermissionSaveReq): Promise<void> {
    return http.put(`/api/system/perm/${permId}`, body)
  },
  remove(permId: number): Promise<void> {
    return http.delete(`/api/system/perm/${permId}`)
  },
  switchStatus(permId: number, status: number): Promise<void> {
    return http.put(`/api/system/perm/${permId}/status?status=${status}`)
  },
}

export const enterpriseApi = {
  /** 全量企业列表（用户筛选/表单下拉用） */
  list(): Promise<SysEnterprise[]> {
    return http.get('/api/system/enterprise/list')
  },
}
```

- [ ] **Step 5: 类型检查**

Run: `cd frontend && npm run build`
Expected: vue-tsc + vite build 通过（不产生类型错误、不产出告警中断）。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/models.ts frontend/src/api/product.ts frontend/src/api/device.ts frontend/src/api/system.ts
git commit -m "feat(web): 产品/设备/RBAC API 模块与类型"
```

---

### Task 4: 产品管理页 Product.vue

**Files:**
- Create: `frontend/src/views/Product.vue`

**Interfaces:**
- Consumes: `productApi`、`deviceTypeOptions`/`productStatusText`、`toLocal`、`Product`/`ProductSaveReq`。
- Produces: 可在 `/product` 路由挂载的产品管理页（Task 9 挂路由）。

- [ ] **Step 1: 编写视图（整文件）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '@/api/product'
import type { Product } from '@/types/models'
import { deviceTypeOptions, productStatusText, thingModelStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'

const loading = ref(false)
const list = ref<Product[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const query = ref({ deviceType: '', status: undefined as number | undefined, keyword: '' })

// 读数带（pageSize=1 轻量计数）
const readout = ref({ total: 0, enabled: 0, disabled: 0 })
async function loadReadout() {
  const [t, on, off] = await Promise.all([
    productApi.page({ pageNum: 1, pageSize: 1 }),
    productApi.page({ pageNum: 1, pageSize: 1, status: 1 }),
    productApi.page({ pageNum: 1, pageSize: 1, status: 0 }),
  ])
  readout.value = { total: t.total, enabled: on.total, disabled: off.total }
}

async function load() {
  loading.value = true
  try {
    const data = await productApi.page({
      pageNum: pageNo.value, pageSize: pageSize.value,
      deviceType: query.value.deviceType || undefined,
      status: query.value.status,
      keyword: query.value.keyword || undefined,
    })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { deviceType: '', status: undefined, keyword: '' }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<Product>>({})
function openCreate() { form.value = { status: 1, protocol: 'MQTT', authType: 'SECRET' }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: Product) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.productKey?.trim() || !form.value.productName?.trim() || !form.value.deviceType) {
    ElMessage.warning('productKey / productName / deviceType 为必填')
    return
  }
  try {
    if (isEdit.value) await productApi.update(form.value.productId!, form.value)
    else await productApi.create(form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: Product) {
  try { await ElMessageBox.confirm(`确定删除产品「${row.productName}」吗？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await productApi.remove(row.productId)
    ElMessage.success('已删除')
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 物模型抽屉
const tmDrawer = ref(false)
const tmProduct = ref<Product | null>(null)
const tmVersion = ref('')
const tmSchema = ref('')
const tmStatus = ref(0)
const tmSaving = ref(false)
async function openThingModel(row: Product) {
  tmProduct.value = row
  tmDrawer.value = true
  tmVersion.value = ''
  tmSchema.value = '{\n  "properties": [],\n  "services": [],\n  "events": []\n}'
  tmStatus.value = 0
  try {
    const view = await productApi.thingModelGet(row.productId)
    tmVersion.value = view.version
    tmSchema.value = view.schemaJson
    tmStatus.value = view.status
  } catch {
    // 未发布：保留空模板提示
  }
}
function formatJson() {
  try { tmSchema.value = JSON.stringify(JSON.parse(tmSchema.value), null, 2) }
  catch { ElMessage.error('JSON 语法错误，无法格式化') }
}
function validateJson(): boolean {
  try { JSON.parse(tmSchema.value); return true }
  catch (e) { ElMessage.error(`JSON 语法错误：${e instanceof Error ? e.message : String(e)}`); return false }
}
async function publishModel() {
  if (!tmProduct.value) return
  if (!tmVersion.value.trim()) { ElMessage.warning('请填写版本号'); return }
  if (!validateJson()) return
  tmSaving.value = true
  try {
    const view = await productApi.thingModelSave(tmProduct.value.productId, {
      version: tmVersion.value.trim(), schemaJson: tmSchema.value,
    })
    tmStatus.value = view.status
    ElMessage.success(`物模型已保存（版本 ${view.version}${view.isCurrent === 1 ? '，当前生效' : ''}）`)
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { tmSaving.value = false }
}

onMounted(() => { void load(); void loadReadout() })
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">产品管理</h1>
        <p class="ex-sub">产品标识 / 设备类型 / 物模型版本 · 决定设备接入协议与认证方式</p>
      </div>
      <el-button type="primary" @click="openCreate">新增产品</el-button>
    </header>

    <section class="ex-readout-band" style="--ro-cols: 3" aria-label="产品统计">
      <div class="ex-readout"><span class="ex-readout-label">产品总数</span><span class="ex-readout-value md"><b>{{ readout.total }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">已启用</span><span class="ex-readout-value md charge"><b>{{ readout.enabled }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">已禁用</span><span class="ex-readout-value md"><b>{{ readout.disabled }}</b></span></div>
    </section>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" clearable placeholder="全部" style="width: 180px">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="产品名 / 产品标识" clearable style="width: 220px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无产品，点击右上角新增">
        <el-table-column prop="productKey" label="productKey" min-width="140" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="deviceType" label="设备类型" width="150">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.deviceType }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="authType" label="认证" width="90" />
        <el-table-column label="物模型版本" width="110">
          <template #default="{ row }"><span class="ex-num">{{ row.modelVersion ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ productStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openThingModel(row)">物模型</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑产品' : '新增产品'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="productKey" required>
          <el-input v-model="form.productKey" placeholder="产品标识，如 snd_ess_pcs（修改影响已接入设备）" maxlength="64" />
        </el-form-item>
        <el-form-item label="产品名称" required>
          <el-input v-model="form.productName" placeholder="产品名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="设备类型" required>
          <el-select v-model="form.deviceType" style="width: 100%">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option label="密钥 SECRET" value="SECRET" />
            <el-option label="证书 CERT" value="CERT" />
          </el-select>
        </el-form-item>
        <el-form-item label="协议">
          <el-input v-model="form.protocol" placeholder="默认 MQTT" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="tmDrawer" size="560px" :title="`物模型 · ${tmProduct?.productName ?? ''}`">
      <div class="tm-head">
        <span>当前状态：<el-tag size="small" :type="tmStatus === 1 ? 'success' : tmStatus === 0 ? 'info' : 'danger'">{{ thingModelStatusText(tmStatus) }}</el-tag></span>
      </div>
      <el-form label-width="70px" class="tm-form">
        <el-form-item label="版本号" required>
          <el-input v-model="tmVersion" placeholder="同版本=覆盖并生效，新版本=发布并切换当前" />
        </el-form-item>
      </el-form>
      <el-input v-model="tmSchema" type="textarea" :rows="14" class="tm-editor" spellcheck="false" />
      <div class="tm-actions">
        <el-button @click="formatJson">格式化</el-button>
        <el-button type="primary" :loading="tmSaving" @click="publishModel">保存发布</el-button>
      </div>
      <p class="tm-note">保存语义由后端处理：同版本覆盖置当前，异版本新增并切换当前，同时回写产品 model_version。</p>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
.tm-form { margin-top: 4px; }
.tm-editor { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; }
.tm-actions { display: flex; gap: 8px; margin-top: 12px; }
.tm-note { font-size: 12px; color: var(--ex-ink-3); margin: 12px 0 0; }
</style>
```

- [ ] **Step 2: 类型检查**

Run: `cd frontend && npm run build`
Expected: 通过。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/Product.vue
git commit -m "feat(web): 产品管理页（CRUD + 物模型 JSON 编辑器/版本）"
```

---

### Task 5: 设备管理页 Device.vue

**Files:**
- Create: `frontend/src/views/Device.vue`

**Interfaces:**
- Consumes: `deviceApi`、`productApi.page`、`enterpriseApi.list`、`deviceStatusText`/`deviceStatusTag`/`deviceTypeOptions`、`toLocal`、`Device`/`DeviceCreateReq`/`DeviceUpdateReq`。

- [ ] **Step 1: 编写视图（整文件）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import { enterpriseApi } from '@/api/system'
import type { CredentialView, Device, Product, SysEnterprise } from '@/types/models'
import { deviceStatusTag, deviceStatusText, deviceTypeOptions } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'

const loading = ref(false)
const list = ref<Device[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const query = ref({ deviceType: '', status: undefined as number | undefined, keyword: '', stationId: undefined as number | undefined })

const readout = ref({ total: 0, online: 0, offline: 0, disabled: 0 })
async function countByStatus(status?: number) {
  return (await deviceApi.page({ pageNum: 1, pageSize: 1, status })).total
}
async function loadReadout() {
  const [t, on, off, dis] = await Promise.all([countByStatus(), countByStatus(3), countByStatus(2), countByStatus(4)])
  readout.value = { total: t, online: on, offline: off, disabled: dis }
}

async function load() {
  loading.value = true
  try {
    const data = await deviceApi.page({
      pageNum: pageNo.value, pageSize: pageSize.value,
      deviceType: query.value.deviceType || undefined,
      status: query.value.status,
      keyword: query.value.keyword || undefined,
      stationId: query.value.stationId ?? undefined,
    })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { deviceType: '', status: undefined, keyword: '', stationId: undefined }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<Device>>({})
const products = ref<Product[]>([])
const enterprises = ref<SysEnterprise[]>([])
async function loadOptions() {
  try {
    const [p, e] = await Promise.all([
      productApi.page({ pageNum: 1, pageSize: 100 }),
      enterpriseApi.list().catch(() => [] as SysEnterprise[]),
    ])
    products.value = p.records
    enterprises.value = e
  } catch { /* 选项加载失败不阻塞页面 */ }
}
function onProductChange(pk?: string) {
  const p = products.value.find((x) => x.productKey === pk)
  if (p) form.value.deviceType = p.deviceType
}
function openCreate() { form.value = { status: 0, protocol: 'MQTT', parentId: 0 }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: Device) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.deviceName?.trim()) { ElMessage.warning('deviceName 为必填'); return }
  if (form.value.deviceName.includes('_') || form.value.deviceName.includes('&')) {
    ElMessage.warning('deviceName 禁止包含 _ 或 &（接入契约）')
    return
  }
  if (!form.value.productKey) { ElMessage.warning('请选择产品'); return }
  try {
    if (isEdit.value) {
      await deviceApi.update(form.value.deviceId!, {
        deviceName: form.value.deviceName, deviceType: form.value.deviceType,
        stationId: form.value.stationId, status: form.value.status,
        firmwareVersion: form.value.firmwareVersion, mac: form.value.mac,
        ip: form.value.ip, sort: form.value.sort,
      })
    } else {
      const id = await deviceApi.create({
        deviceName: form.value.deviceName!.trim(),
        deviceType: form.value.deviceType!,
        productKey: form.value.productKey!,
        parentId: form.value.parentId ?? 0,
        stationId: form.value.stationId ?? undefined,
        enterpriseId: form.value.enterpriseId ?? undefined,
        firmwareVersion: form.value.firmwareVersion || undefined,
        mac: form.value.mac || undefined,
        ip: form.value.ip || undefined,
        sort: form.value.sort ?? 0,
        status: form.value.status,
        protocol: form.value.protocol || 'MQTT',
      })
      ElMessage.success(`创建成功，设备 ID=${id}，凭据已生成——请打开该设备详情查看/复制`)
    }
    dialogVisible.value = false
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 详情抽屉 + 凭据
const drawerVisible = ref(false)
const detail = ref<Device | null>(null)
const cred = ref<CredentialView | null>(null)
const plainSecret = ref('')
async function openDetail(row: Device) {
  detail.value = row
  drawerVisible.value = true
  cred.value = null
  plainSecret.value = ''
  try {
    const [d, c] = await Promise.all([deviceApi.detail(row.deviceId), deviceApi.credential(row.deviceId)])
    detail.value = d
    cred.value = c
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function regenerateSecret() {
  if (!detail.value) return
  try { await ElMessageBox.confirm('重新生成将吊销旧密钥，设备需用新密钥重连。确定继续吗？', '提示', { type: 'warning' }) } catch { return }
  try {
    cred.value = await deviceApi.regenerateCredential(detail.value.deviceId)
    plainSecret.value = cred.value.deviceSecret
    ElMessage.success('新密钥已生成（仅本次明文展示）')
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
function copySecret() {
  if (!plainSecret.value) return
  void navigator.clipboard?.writeText(plainSecret.value)
  ElMessage.success('已复制')
}
function onlineSeconds(sec?: number | null): string {
  if (!sec || sec <= 0) return '-'
  const d = Math.floor(sec / 86400); const h = Math.floor((sec % 86400) / 3600); const m = Math.floor((sec % 3600) / 60)
  return `${d}天${h}时${m}分`
}

async function remove(row: Device) {
  try { await ElMessageBox.confirm(`确定删除设备「${row.deviceName}」吗？将级联删除其整棵子树并吊销凭据。`, '提示', { type: 'warning' }) } catch { return }
  try {
    await deviceApi.remove(row.deviceId)
    ElMessage.success('已删除')
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(() => { void load(); void loadReadout(); void loadOptions() })
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">设备管理</h1>
        <p class="ex-sub">设备树统一建模 · 状态 0未注册~5封禁 · 凭据仅查询时脱敏展示</p>
      </div>
      <el-button type="primary" @click="openCreate">新增设备</el-button>
    </header>

    <section class="ex-readout-band" style="--ro-cols: 4" aria-label="设备状态统计">
      <div class="ex-readout"><span class="ex-readout-label">设备总数</span><span class="ex-readout-value md"><b>{{ readout.total }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">在线</span><span class="ex-readout-value md charge"><b>{{ readout.online }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">离线</span><span class="ex-readout-value md"><b>{{ readout.offline }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">禁用</span><span class="ex-readout-value md danger"><b>{{ readout.disabled }}</b></span></div>
    </section>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" clearable placeholder="全部" style="width: 170px">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="i in [0, 1, 2, 3, 4, 5]" :key="i" :label="deviceStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站 ID">
          <el-input-number v-model="query.stationId" :min="1" :controls="false" placeholder="全部" style="width: 120px" />
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="设备名模糊" clearable style="width: 200px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无设备" @row-click="openDetail">
        <el-table-column prop="deviceId" label="deviceId" width="130" show-overflow-tooltip>
          <template #default="{ row }"><span class="ex-num">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column prop="deviceName" label="设备名" min-width="140" show-overflow-tooltip />
        <el-table-column prop="productKey" label="productKey" width="130" show-overflow-tooltip />
        <el-table-column prop="deviceType" label="类型" width="130" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="deviceStatusTag(row.status)" size="small">{{ deviceStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stationId" label="电站" width="80">
          <template #default="{ row }"><span class="ex-num">{{ row.stationId ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="最近上线" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.lastOnlineTime) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click.stop="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑设备' : '新增设备'" width="600px">
      <el-form label-width="110px">
        <el-form-item label="设备名" required>
          <el-input v-model="form.deviceName" placeholder="如 sim-dev-000001（禁止 _ 与 &）" maxlength="128" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="产品" required>
          <el-select v-model="form.productKey" filterable style="width: 100%" :disabled="isEdit" @change="onProductChange">
            <el-option v-for="p in products" :key="p.productKey" :label="`${p.productName} (${p.productKey})`" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="form.deviceType" style="width: 100%" :disabled="isEdit">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属企业">
          <el-select v-model="form.enterpriseId" clearable filterable placeholder="无" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站 ID">
          <el-input-number v-model="form.stationId" :min="1" :controls="false" style="width: 200px" />
        </el-form-item>
        <el-form-item label="固件版本">
          <el-input v-model="form.firmwareVersion" placeholder="如 v1.2.0" maxlength="64" />
        </el-form-item>
        <el-form-item label="MAC / IP">
          <el-input v-model="form.mac" placeholder="MAC" style="width: 48%" />
          <el-input v-model="form.ip" placeholder="IP" style="width: 48%; margin-left: 4%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 200px">
            <el-option v-for="i in [0, 1, 2, 3, 4, 5]" :key="i" :label="deviceStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" size="480px" :title="`设备详情 · ${detail?.deviceName ?? ''}`">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="desc">
          <el-descriptions-item label="deviceId" :span="2"><span class="ex-num">{{ detail.deviceId }}</span></el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="deviceStatusTag(detail.status)" size="small">{{ deviceStatusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.deviceType }}</el-descriptions-item>
          <el-descriptions-item label="productKey" :span="2">{{ detail.productKey }}</el-descriptions-item>
          <el-descriptions-item label="电站">{{ detail.stationId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="企业">{{ detail.enterpriseId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="父设备" :span="2"><span class="ex-num">{{ detail.parentId }}</span></el-descriptions-item>
          <el-descriptions-item label="路径" :span="2">{{ detail.path }}</el-descriptions-item>
          <el-descriptions-item label="层级" :span="2"><span class="ex-num">{{ detail.level }}</span></el-descriptions-item>
          <el-descriptions-item label="固件">{{ detail.firmwareVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="协议">{{ detail.protocol }}</el-descriptions-item>
          <el-descriptions-item label="MAC" :span="2">{{ detail.mac ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="IP" :span="2">{{ detail.ip ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="累计在线" :span="2">{{ onlineSeconds(detail.onlineSeconds) }}</el-descriptions-item>
          <el-descriptions-item label="最近上线" :span="2"><span class="ex-num">{{ toLocal(detail.lastOnlineTime) }}</span></el-descriptions-item>
          <el-descriptions-item label="最近下线" :span="2"><span class="ex-num">{{ toLocal(detail.lastOfflineTime) }}</span></el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2"><span class="ex-num">{{ toLocal(detail.createTime) }}</span></el-descriptions-item>
        </el-descriptions>

        <div class="ex-card cred-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">连接凭据</h2>
            <el-button size="small" type="warning" @click="regenerateSecret">重新生成</el-button>
          </div>
          <template v-if="cred">
            <p class="cred-line">
              <span class="cred-label">密钥（脱敏）</span>
              <code class="cred-mask">{{ cred.deviceSecret }}</code>
              <el-tag size="small" :type="cred.authStatus === 1 ? 'success' : 'danger'">{{ cred.authStatus === 1 ? '正常' : '吊销' }}</el-tag>
            </p>
            <el-alert v-if="plainSecret" type="warning" :closable="false" class="cred-plain">
              <template #title>
                新密钥（仅本次展示，请立即复制保存）
                <el-button link type="primary" size="small" @click="copySecret">复制</el-button>
              </template>
              <code>{{ plainSecret }}</code>
            </el-alert>
          </template>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
.desc { margin-bottom: 14px; }
.cred-card { padding-bottom: 14px; }
.cred-line { display: flex; align-items: center; gap: 10px; padding: 12px 18px 0; margin: 0; font-size: 13px; }
.cred-label { color: var(--ex-ink-2); flex: none; }
.cred-mask { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; color: var(--ex-ink); }
.cred-plain { margin: 12px 18px 0; }
.cred-plain code { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; word-break: break-all; }
</style>
```

- [ ] **Step 2: 类型检查**

Run: `cd frontend && npm run build`
Expected: 通过。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/Device.vue
git commit -m "feat(web): 设备管理页（筛选表 + 详情抽屉 + 凭据查看/重生成）"
```

---

### Task 6: 用户管理页 SystemUser.vue

**Files:**
- Create: `frontend/src/views/SystemUser.vue`

**Interfaces:**
- Consumes: `userApi`/`roleApi`/`enterpriseApi`、`userStatusText`/`userStatusTag`、`toLocal`、`SysUserVO`/`SysRole`/`SysEnterprise`、`useAuthStore`（当前登录 userId，禁用删自身/超管按钮）。

- [ ] **Step 1: 编写视图（整文件）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { enterpriseApi, roleApi, userApi } from '@/api/system'
import type { SysEnterprise, SysRole, SysUserVO } from '@/types/models'
import { userStatusTag, userStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const list = ref<SysUserVO[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const query = ref({ keyword: '', status: undefined as number | undefined, enterpriseId: undefined as number | undefined })
const roles = ref<SysRole[]>([])
const enterprises = ref<SysEnterprise[]>([])

async function load() {
  loading.value = true
  try {
    const data = await userApi.page({
      current: pageNo.value, size: pageSize.value,
      keyword: query.value.keyword || undefined,
      status: query.value.status,
      enterpriseId: query.value.enterpriseId,
    })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { keyword: '', status: undefined, enterpriseId: undefined }; pageNo.value = 1; void load() }

/** 超管或当前登录账号：禁用删除/启停 */
function isProtected(row: SysUserVO): boolean {
  return row.userId === 1 || row.userId === authStore.user?.userId
}

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<SysUserVO> & { password?: string; roleIds?: number[] }>({})
function openCreate() { form.value = { status: 1, roleIds: [] }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: SysUserVO) { form.value = { ...row, password: '', roleIds: [...row.roleIds] }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.username?.trim() || !form.value.realName?.trim()) { ElMessage.warning('用户名 / 姓名 为必填'); return }
  if (!/^[a-zA-Z0-9_.-]+$/.test(form.value.username)) { ElMessage.warning('用户名仅允许字母数字 _ . -'); return }
  if (!isEdit.value && (!form.value.password || form.value.password.length < 6)) { ElMessage.warning('创建时密码必填（6~64 位）'); return }
  try {
    const body = {
      username: form.value.username, realName: form.value.realName,
      phone: form.value.phone || undefined, email: form.value.email || undefined,
      enterpriseId: form.value.enterpriseId ?? undefined,
      status: form.value.status,
      password: form.value.password || undefined,
      roleIds: form.value.roleIds && form.value.roleIds.length ? form.value.roleIds : undefined,
    }
    if (isEdit.value) await userApi.update(form.value.userId!, body)
    else await userApi.create(body)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 分配角色
const roleDialog = ref(false)
const target = ref<SysUserVO | null>(null)
const checkedRoles = ref<number[]>([])
async function openRoles(row: SysUserVO) {
  target.value = row
  roleDialog.value = true
  try {
    checkedRoles.value = await userApi.roles(row.userId)
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function saveRoles() {
  if (!target.value) return
  try {
    await userApi.assignRoles(target.value.userId, checkedRoles.value)
    ElMessage.success('角色已更新')
    roleDialog.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 重置密码
const pwdDialog = ref(false)
const pwdUser = ref<SysUserVO | null>(null)
const pwdValue = ref('')
async function openPwd(row: SysUserVO) { pwdUser.value = row; pwdValue.value = ''; pwdDialog.value = true }
async function savePwd() {
  if (!pwdUser.value) return
  if (!pwdValue.value || pwdValue.value.length < 6) { ElMessage.warning('密码长度 6~64'); return }
  try {
    await userApi.resetPassword(pwdUser.value.userId, pwdValue.value)
    ElMessage.success('密码已重置')
    pwdDialog.value = false
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

async function switchStatus(row: SysUserVO, status: number) {
  try {
    await userApi.switchStatus(row.userId, status)
    ElMessage.success(status === 1 ? '已启用' : '已禁用')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: SysUserVO) {
  try { await ElMessageBox.confirm(`确定删除用户「${row.username}」吗？其在线会话将被吊销。`, '提示', { type: 'warning' }) } catch { return }
  try {
    await userApi.remove(row.userId)
    ElMessage.success('已删除')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(async () => {
  void load()
  try {
    const [r, e] = await Promise.all([roleApi.list(), enterpriseApi.list().catch(() => [] as SysEnterprise[])])
    roles.value = r
    enterprises.value = e
  } catch { /* 下拉加载失败不阻塞 */ }
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">用户管理</h1>
        <p class="ex-sub">RBAC 用户 · 分配角色决定可见菜单与操作权限</p>
      </div>
      <el-button type="primary" @click="openCreate">新增用户</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="用户名 / 姓名" clearable style="width: 200px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="i in [0, 1, 2]" :key="i" :label="userStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="企业">
          <el-select v-model="query.enterpriseId" clearable filterable placeholder="全部" style="width: 180px">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无用户">
        <el-table-column prop="username" label="用户名" min-width="110" />
        <el-table-column prop="realName" label="姓名" min-width="90" />
        <el-table-column prop="phone" label="电话" width="120" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="userStatusTag(row.status)" size="small">{{ userStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="n in row.roleNames" :key="n" size="small" effect="plain" class="role-tag">{{ n }}</el-tag>
            <span v-if="!row.roleNames?.length" class="ex-ink-3">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="enterpriseName" label="企业" min-width="110">
          <template #default="{ row }">{{ row.enterpriseName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="最近登录" width="140">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.lastLoginTime) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openRoles(row)">分配角色</el-button>
            <el-button link type="warning" @click="openPwd(row)">重置密码</el-button>
            <el-button v-if="!isProtected(row)" link :type="row.status === 1 ? 'warning' : 'success'" @click="switchStatus(row, row.status === 1 ? 0 : 1)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button v-if="!isProtected(row)" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" placeholder="字母数字 _ . -，如 operator" :disabled="isEdit" maxlength="64" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.realName" placeholder="真实姓名" maxlength="64" />
        </el-form-item>
        <el-form-item label="所属企业">
          <el-select v-model="form.enterpriseId" clearable filterable placeholder="无" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple clearable placeholder="不分配角色" style="width: 100%">
            <el-option v-for="r in roles" :key="r.roleId" :label="r.roleName" :value="r.roleId" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="form.phone" maxlength="32" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="密码" :required="!isEdit">
          <el-input v-model="form.password" type="password" show-password :placeholder="isEdit ? '留空则不修改' : '6~64 位'" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialog" :title="`分配角色 · ${target?.username ?? ''}`" width="420px">
      <el-select v-model="checkedRoles" multiple style="width: 100%" placeholder="选择角色">
        <el-option v-for="r in roles" :key="r.roleId" :label="`${r.roleName} (${r.roleCode})`" :value="r.roleId" />
      </el-select>
      <template #footer>
        <el-button @click="roleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdDialog" :title="`重置密码 · ${pwdUser?.username ?? ''}`" width="420px">
      <el-input v-model="pwdValue" type="password" show-password placeholder="新密码 6~64 位" maxlength="64" @keyup.enter="savePwd" />
      <template #footer>
        <el-button @click="pwdDialog = false">取消</el-button>
        <el-button type="primary" @click="savePwd">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
.role-tag { margin-right: 4px; }
.ex-ink-3 { color: var(--ex-ink-3); }
</style>
```

- [ ] **Step 2: 类型检查**

Run: `cd frontend && npm run build`
Expected: 通过。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/SystemUser.vue
git commit -m "feat(web): 用户管理页（CRUD + 分配角色 + 重置密码 + 启停）"
```

---

### Task 7: 角色管理页 SystemRole.vue

**Files:**
- Create: `frontend/src/views/SystemRole.vue`

**Interfaces:**
- Consumes: `roleApi`/`permApi`、`roleStatusText`/`roleStatusTag`/`dataScopeText`、`toLocal`、`SysRole`/`SysPermission`。

- [ ] **Step 1: 编写视图（整文件）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, ElTree } from 'element-plus'
import { permApi, roleApi } from '@/api/system'
import type { SysPermission, SysRole } from '@/types/models'
import { dataScopeText, roleStatusTag, roleStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'

const loading = ref(false)
const list = ref<SysRole[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const query = ref({ keyword: '', status: undefined as number | undefined })

async function load() {
  loading.value = true
  try {
    const data = await roleApi.page({ current: pageNo.value, size: pageSize.value, keyword: query.value.keyword || undefined, status: query.value.status })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { keyword: '', status: undefined }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<SysRole>>({})
function openCreate() { form.value = { status: 1, dataScope: 3 }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: SysRole) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.roleCode?.trim() || !form.value.roleName?.trim()) { ElMessage.warning('角色编码 / 名称 为必填'); return }
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(form.value.roleCode)) { ElMessage.warning('角色编码需以字母开头，仅字母数字下划线'); return }
  try {
    if (isEdit.value) await roleApi.update(form.value.roleId!, form.value)
    else await roleApi.create(form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 授权权限
const permDialog = ref(false)
const permRole = ref<SysRole | null>(null)
const permTree = ref<SysPermission[]>([])
/** el-tree 组件实例：Element Plus 官方模板 ref 模式（InstanceType<typeof ElTree>） */
const permRef = ref<InstanceType<typeof ElTree>>()
async function openPerms(row: SysRole) {
  permRole.value = row
  permDialog.value = true
  try {
    const [tree, ids] = await Promise.all([permApi.tree(), roleApi.perms(row.roleId)])
    permTree.value = tree
    // 等树渲染后回显（nextTick 确保节点已挂载）
    await new Promise((r) => setTimeout(r, 0))
    permRef.value?.setCheckedKeys(ids)
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function savePerms() {
  if (!permRole.value) return
  try {
    const checked = permRef.value?.getCheckedKeys(false) as number[] ?? []
    const half = permRef.value?.getHalfCheckedKeys() as number[] ?? []
    await roleApi.assignPerms(permRole.value.roleId, [...checked, ...half])
    ElMessage.success('权限已更新')
    permDialog.value = false
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

async function switchStatus(row: SysRole, status: number) {
  try {
    await roleApi.switchStatus(row.roleId, status)
    ElMessage.success(status === 1 ? '已启用' : '已停用')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: SysRole) {
  try { await ElMessageBox.confirm(`确定删除角色「${row.roleName}」吗？已分配该角色的用户需重新授权。`, '提示', { type: 'warning' }) } catch { return }
  try {
    await roleApi.remove(row.roleId)
    ElMessage.success('已删除')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(load)
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">角色管理</h1>
        <p class="ex-sub">角色承载权限集合 · dataScope 决定数据可见范围</p>
      </div>
      <el-button type="primary" @click="openCreate">新增角色</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="角色编码 / 名称" clearable style="width: 220px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="i in [0, 1]" :key="i" :label="roleStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无角色">
        <el-table-column prop="roleCode" label="角色编码" min-width="130" />
        <el-table-column prop="roleName" label="角色名称" min-width="140" />
        <el-table-column label="数据范围" width="100">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ dataScopeText(row.dataScope) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="roleStatusTag(row.status)" size="small">{{ roleStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.roleId !== 1" link type="primary" @click="openPerms(row)">授权权限</el-button>
            <el-button v-if="row.roleId !== 1" link :type="row.status === 1 ? 'warning' : 'success'" @click="switchStatus(row, row.status === 1 ? 0 : 1)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="row.roleId !== 1" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="角色编码" required>
          <el-input v-model="form.roleCode" placeholder="如 OPERATOR（字母开头）" maxlength="64" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.roleName" placeholder="如 运维操作员" maxlength="64" />
        </el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option label="本人" :value="1" />
            <el-option label="本企业" :value="2" />
            <el-option label="本租户" :value="3" />
            <el-option label="全部" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permDialog" :title="`授权权限 · ${permRole?.roleName ?? ''}`" width="520px">
      <el-scrollbar max-height="420px">
        <el-tree ref="permRef" :data="permTree" node-key="permId" show-checkbox default-expand-all
          :props="{ label: 'permName', children: 'children' }" />
      </el-scrollbar>
      <template #footer>
        <el-button @click="permDialog = false">取消</el-button>
        <el-button type="primary" @click="savePerms">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
</style>
```

> 说明：`ElTree` 仅在类型位置使用（`typeof ElTree`），esbuild/vite 会自动擦除该值导入，不会进入产物；`el-tree` 组件本体由 Element Plus 插件全局注册。

- [ ] **Step 2: 类型检查**

Run: `cd frontend && npm run build`
Expected: 通过。（若 `ElTree` 类型断言报错，按 Step 1 末尾说明改用法后重跑。）

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/SystemRole.vue
git commit -m "feat(web): 角色管理页（CRUD + 授权权限树）"
```

---

### Task 8: 菜单权限管理页 SystemPerm.vue

**Files:**
- Create: `frontend/src/views/SystemPerm.vue`

**Interfaces:**
- Consumes: `permApi`、`permTypeText`、`SysPermission`/`SysPermissionSaveReq`。

- [ ] **Step 1: 编写视图（整文件）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { permApi } from '@/api/system'
import type { SysPermission } from '@/types/models'
import { permTypeText } from '@/utils/dicts'

const loading = ref(false)
const tree = ref<SysPermission[]>([])

async function load() {
  loading.value = true
  try {
    tree.value = await permApi.tree()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<SysPermission>>({})
function openCreate(parent?: SysPermission) {
  form.value = { parentId: parent?.permId ?? 0, permType: 1, status: 0, visible: 0, sort: 0 }
  isEdit.value = false
  dialogVisible.value = true
}
function openEdit(row: SysPermission) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.permCode?.trim() || !form.value.permName?.trim()) { ElMessage.warning('权限编码 / 名称 为必填'); return }
  try {
    if (isEdit.value) await permApi.update(form.value.permId!, form.value)
    else await permApi.create(form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

async function switchStatus(row: SysPermission, status: number) {
  try {
    await permApi.switchStatus(row.permId, status)
    ElMessage.success(status === 0 ? '已启用' : '已停用')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: SysPermission) {
  if (row.children?.length) { ElMessage.warning('存在子节点，请先删除子节点'); return }
  try { await ElMessageBox.confirm(`确定删除权限「${row.permName}」吗？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await permApi.remove(row.permId)
    ElMessage.success('已删除')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(load)
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">菜单权限</h1>
        <p class="ex-sub">菜单 / 按钮 / 数据权限树 · 决定侧边栏可见性与操作按钮</p>
      </div>
      <el-button type="primary" @click="openCreate()">新增权限</el-button>
    </header>

    <section class="ex-card table-card">
      <el-table :data="tree" v-loading="loading" size="small" row-key="permId"
        :tree-props="{ children: 'children' }" default-expand-all empty-text="暂无权限数据">
        <el-table-column prop="permName" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="permCode" label="权限编码" min-width="160" show-overflow-tooltip>
          <template #default="{ row }"><code class="perm-code">{{ row.permCode }}</code></template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.permType === 1 ? 'primary' : row.permType === 2 ? 'success' : 'info'">{{ permTypeText(row.permType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.path ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="icon" label="图标" width="80">
          <template #default="{ row }">{{ row.icon ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="可见" width="70">
          <template #default="{ row }">{{ row.visible === 0 ? '显示' : '隐藏' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">{{ row.status === 0 ? '正常' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="70">
          <template #default="{ row }"><span class="ex-num">{{ row.sort }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCreate(row)">新增子</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 0 ? 'warning' : 'success'" @click="switchStatus(row, row.status === 0 ? 1 : 0)">
              {{ row.status === 0 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑权限' : '新增权限'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="父级">
          <el-tree-select v-model="form.parentId" :data="[{ permId: 0, permName: '顶级', children: tree }]"
            node-key="permId" :props="{ label: 'permName', children: 'children' }" check-strictly
            default-expand-all clearable style="width: 100%" placeholder="顶级" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.permType">
            <el-radio :value="1">菜单</el-radio>
            <el-radio :value="2">按钮</el-radio>
            <el-radio :value="3">数据</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="权限编码" required>
          <el-input v-model="form.permCode" placeholder="如 system:user:add（全局唯一）" maxlength="100" />
        </el-form-item>
        <el-form-item label="权限名称" required>
          <el-input v-model="form.permName" placeholder="如 用户新增" maxlength="64" />
        </el-form-item>
        <el-form-item label="资源类型">
          <el-select v-model="form.resourceType" clearable placeholder="不限" style="width: 100%">
            <el-option v-for="t in ['DEVICE', 'STRATEGY', 'ALARM', 'STATION']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.permType === 1" label="路由">
          <el-input v-model="form.path" placeholder="如 /system/user" maxlength="200" />
        </el-form-item>
        <el-form-item v-if="form.permType === 1" label="图标">
          <el-input v-model="form.icon" placeholder="如 Setting" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="form.permType === 1" label="组件">
          <el-input v-model="form.component" placeholder="如 system/user/index" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" style="width: 160px" />
        </el-form-item>
        <el-form-item label="可见">
          <el-radio-group v-model="form.visible">
            <el-radio :value="0">显示</el-radio>
            <el-radio :value="1">隐藏</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="0">正常</el-radio>
            <el-radio :value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="256" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-card { padding-bottom: 10px; }
.perm-code { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; color: var(--ex-ink-2); }
</style>
```

- [ ] **Step 2: 类型检查**

Run: `cd frontend && npm run build`
Expected: 通过。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/SystemPerm.vue
git commit -m "feat(web): 菜单权限管理页（权限树 CRUD）"
```

---

### Task 9: 路由 + 侧边栏（挂载全部新页面 + 权限过滤）

**Files:**
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/MainLayout.vue`

**Interfaces:**
- Consumes: `hasPermi`（Task 1）、`useAuthStore`（已有）、5 个新视图（Task 4-8）。
- Produces: `/product`、`/device`、`/system/user`、`/system/role`、`/system/perm` 5 条可用路由；侧边栏新增「产品管理」「设备管理」与「系统管理」分组并随权限过滤。

- [ ] **Step 1: 路由追加 5 条**

在 `frontend/src/router/index.ts` 的 `MainLayout` children 数组末尾（`ems/plan` 之后）追加：

```ts
      {
        path: 'product',
        name: 'Product',
        component: () => import('@/views/Product.vue'),
        meta: { title: '产品管理', icon: 'Box' },
      },
      {
        path: 'device',
        name: 'Device',
        component: () => import('@/views/Device.vue'),
        meta: { title: '设备管理', icon: 'Cpu' },
      },
      {
        path: 'system/user',
        name: 'SystemUser',
        component: () => import('@/views/SystemUser.vue'),
        meta: { title: '用户管理', icon: 'User' },
      },
      {
        path: 'system/role',
        name: 'SystemRole',
        component: () => import('@/views/SystemRole.vue'),
        meta: { title: '角色管理', icon: 'UserFilled' },
      },
      {
        path: 'system/perm',
        name: 'SystemPerm',
        component: () => import('@/views/SystemPerm.vue'),
        meta: { title: '菜单权限', icon: 'Lock' },
      },
```

- [ ] **Step 2: 侧边栏支持分组 + 权限过滤**

在 `frontend/src/layouts/MainLayout.vue`：

**script** —— 把现有 `menus` 数组替换为（保留原 6 项，新增 3 项 + 系统管理分组；`icon` 字段保留但模板不渲染，与现状一致）：

```ts
import { hasPermi } from '@/utils/permission'

interface MenuItem {
  path?: string
  title: string
  perms?: string[]
  group?: string
  children?: MenuItem[]
}

const menus: MenuItem[] = [
  { path: '/dashboard', title: '设备监控' },
  { path: '/shadow', title: '影子' },
  { path: '/command', title: '指令中心' },
  { path: '/alarm', title: '告警中心' },
  { path: '/product', title: '产品管理' },
  { path: '/device', title: '设备管理' },
  { path: '/ems/strategy', title: '策略管理' },
  { path: '/ems/plan', title: '充放电计划' },
  {
    group: '/system',
    title: '系统管理',
    children: [
      { path: '/system/user', title: '用户管理', perms: ['system:user:list'] },
      { path: '/system/role', title: '角色管理', perms: ['system:role:list'] },
      { path: '/system/perm', title: '菜单权限', perms: ['system:perm:list'] },
    ],
  },
]

/** 按当前用户权限过滤后的可见菜单：先过滤各组子项，再剔除空组与无权限的普通项 */
const visibleMenus = computed(() =>
  menus
    .map((m) => (m.children
      ? { ...m, children: m.children.filter((c) => hasPermi(authStore.permissions, c.perms)) }
      : m))
    .filter((m) => (m.children ? m.children.length > 0 : hasPermi(authStore.permissions, m.perms))),
)
```

> `authStore.permissions` 已是响应式 ref（setup store 自动解包）；`computed` 已在文件顶部 import。

**template** —— 把 `<el-menu>` 块替换为：

```html
<el-menu :default-active="route.path" router class="menu" :default-openeds="['/system']">
  <template v-for="m in visibleMenus" :key="m.path ?? m.group">
    <el-sub-menu v-if="m.children && m.children.length" :index="m.group ?? m.title">
      <template #title><span>{{ m.title }}</span></template>
      <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path!">
        <span>{{ c.title }}</span>
      </el-menu-item>
    </el-sub-menu>
    <el-menu-item v-else :index="m.path!">
      <span>{{ m.title }}</span>
    </el-menu-item>
  </template>
</el-menu>
```

**style** —— 在 `<style scoped>` 内 `.menu` 规则之后追加（分组标题与一级项同尺度，箭头保持 Element 默认）：

```css
.menu :deep(.el-sub-menu__title) {
  margin: 2px 10px;
  border-radius: 5px;
  font-size: 14px;
  height: 40px;
  line-height: 40px;
  color: var(--ex-ink-2);
}
```

- [ ] **Step 3: 类型检查**

Run: `cd frontend && npm run build`
Expected: 通过。

- [ ] **Step 4: 手工冒烟（需后端全栈在跑，`npm run dev` 打开 25173）**

1. admin/admin123 登录 → 侧边栏含 产品管理/设备管理/系统管理（展开含 用户/角色/菜单权限），原有 6 项仍在；
2. 逐一打开 5 个新页面，表格加载、筛选条、弹窗可开；
3. 用无 `system:user:list` 权限的账号登录 → 系统管理分组整体隐藏，产品/设备仍可见。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/router/index.ts frontend/src/layouts/MainLayout.vue
git commit -m "feat(web): 挂载产品/设备/RBAC 路由与侧边栏权限过滤"
```

---

### Task 10: 全量回归 + 文档

**Files:**
- Test: `frontend/src/utils/__tests__/permission.spec.ts`、`frontend/src/utils/__tests__/dicts.spec.ts`（已有）
- Modify: `README.md`（文档导航，可选）

- [ ] **Step 1: 单测全量**

Run: `cd frontend && npm run test`
Expected: 全部通过（含既有 http/auth/alarm 单测，无回归）。

- [ ] **Step 2: 类型 + 构建**

Run: `cd frontend && npm run build`
Expected: 通过。

- [ ] **Step 3: 端到端冒烟（覆盖 spec §8 清单）**

1. 产品：新增（PCS 类型）→ 打开物模型 → 填版本号与 JSON → 格式化/校验/保存发布 → 列表显示版本号；
2. 设备：新增（选该产品，自动带 deviceType）→ 详情抽屉看脱敏凭据 → 重新生成 → 明文 + 复制 → 删除（confirm）；
3. 用户：新增（分配角色）→ 分配角色弹窗改选 → 重置密码 → 启停 → 删除；
4. 角色：新增 → 授权权限树勾选/回显 → 保存；
5. 菜单权限：新增子节点 → 有子节点删除被前端拦截提示；
6. 权限过滤：无 `system:user:list` 账号看不到系统管理。

- [ ] **Step 4: 更新文档导航（可选）**

在 `README.md` 文档导航追加一行（若有前端页清单/设计文档索引则同步补）：

```markdown
- 管理后台页面设计：`docs/superpowers/specs/2026-08-08-admin-pages-design.md`
```

- [ ] **Step 5: Commit**

```bash
git add README.md frontend/src/utils/__tests__ frontend/src/views
git commit -m "docs: 管理后台页面完成，全量回归通过"
```
