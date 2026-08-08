# EnergyX 管理后台页面（产品 / 设备 / RBAC）设计

> 目标：为 EnergyX 储能管理平台补齐产品管理、设备管理、RBAC（用户/角色/菜单权限）共 5 个管理页面，页面风格与现有 EnergyX 仪表设计语言（`--ex-*` token + 仪器纸面 + Bahnschrift 数字）保持一致；随后给出策略/计划模块的人性化优化评估（阶段二，建议，不在本期实现）。

---

## 1. 背景与目标

前端现有业务页（设备监控/影子/指令/告警/策略/计划）均为「管理+监视」型页面，但没有**配置管理**入口：产品、设备、账号、角色、菜单权限都只能直接打 API。本期补齐这 5 个管理页面，形成「设备侧操作 → 资产管理 → 账号权限」的完整前端闭环。

**成功标准：**
- 5 个页面在侧边栏可见、可操作，CRUD + 关键业务动作（设备凭据重生成、角色授权、用户分配角色）全部可用；
- 页面风格与现有页面逐 token 一致（复用 `ex-page`/`ex-card`/`ex-readout-band` 结构件，不新造样式体系）；
- 侧边栏按当前用户 `permissions` 过滤：无 `system:user:list` 的用户看不到「用户管理」菜单；product/device 菜单恒显（后端本就仅要求登录）。

## 2. 范围

### 本期实现（In）
- 路由 + 菜单 + 权限过滤（`hasPermi`）
- 产品管理页（CRUD + 物模型 JSON 编辑器/版本）
- 设备管理页（筛选表 + 详情抽屉 + 凭据查看/重生成）
- 用户管理页（CRUD + 分配角色 + 重置密码 + 启停）
- 角色管理页（CRUD + 授权权限树）
- 菜单权限管理页（权限树 CRUD）
- API 模块与类型（`product.ts` / `device.ts` / `system.ts`）

### 不做（Out / YAGNI）
- **不动后端**：本期纯前端。已知后端 API 约束全部兼容（分页参数两套、`/tenant` 无网关路由、`PUT role/perms` 全量覆盖数组等）。
- 不做动态路由/后端驱动菜单（已确认：静态菜单 + 按权限过滤）。
- 不做结构化物模型编辑器（已确认：JSON 编辑器 + 版本管理）。
- 不做设备树形态（已确认：筛选表 + 详情抽屉）。
- 不做租户管理页（无网关路由，且不在 RBAC 三件套范围）。
- 阶段二 EMS 优化只出评估与建议，不在本期实现。

## 3. 后端 API 契约（前端依赖，已盘点）

### 3.1 公共约定
- 统一响应 `Result<T>`：`{code,message,data,traceId,timestamp}`，`code=0` 成功；前端 `http.ts` 拦截器已解包 `data`、失败抛 `message`、HTTP 401 清 token 跳登录。
- 分页响应统一 `PageResult<T>`：`{total,pages,current,size,records}`。
- **请求分页参数两套**（前端各自封装，页面不感知）：
  - product / device → `pageNum` / `pageSize`（默认 20）
  - system 的 user / role → `current` / `size`（默认 10，封顶 100）
- 网关 `GlobalAuthFilter` 强制 JWT，透传 `x-tenant-id` 等头，下游自动拼租户条件——前端请求体/查询**不带 tenantId**。
- 逻辑删除（`@TableLogic`，分页自动过滤 `deleted=1`）：product / device / user / enterprise；**物理删除**：role / perm。
- 鉴权：product / device 无 `@PreAuthorize`，仅需登录；system 模块有方法级权限码。

### 3.2 网关路由
`/api/product/**`、`/api/device/**`、`/api/system/**` → StripPrefix=1 → 下游 controller 前缀 `/product/**`、`/device/**`、`/system/**`。前端一律走 `/api/...`。

### 3.3 权限码（按钮级 / 菜单级过滤用）
- `system:user:list / add / edit / remove / resetPwd / role`
- `system:role:list / add / edit / remove / perm`
- `system:perm:list / add / edit / remove`
- `system:enterprise:list`
- 超管权限串：`*:*:*`（`LoginResult.permissions` 直接给出）

### 3.4 关键端点摘要（详见各页面节）

| 模块 | 端点 | 说明 |
|---|---|---|
| 产品 | `GET /product/page` `POST /product` `GET /product/{id}` `PUT /product/{id}` `DELETE /product/{id}` | page 参数 `pageNum/pageSize/deviceType/keyword/status`；创建/更新 body `ProductSaveReq` |
| 物模型 | `GET /product/{id}/thing-model` `PUT /product/{id}/thing-model` | 单版本视图；未发布返回 NOT_FOUND；save body `{version, schemaJson}`，同版本覆盖/异版本切换当前 |
| 设备 | `GET /device/page` `POST /device` `GET /device/{id}` `PUT /device/{id}` `DELETE /device/{id}` | page 参数 `pageNum/pageSize/stationId/deviceType/parentId/status/keyword/enterpriseId`（**无 productKey 过滤**）；创建返回 `Long deviceId` |
| 设备凭据 | `GET /device/{id}/credential` `POST /device/{id}/credential/regenerate` | 查询返回**脱敏**密钥；重生成返回**明文**（仅此一次） |
| 用户 | `GET /system/user/page` `POST /system/user` `PUT /system/user/{id}` `DELETE /system/user/{id}` `PUT /system/user/{id}/status?status=` `PUT /system/user/{id}/password` | page 参数 `current/size/keyword/status/enterpriseId`；body `SysUserSaveReq`（密码创建必填、编辑留空不改；`roleIds` null=不改、数组=全量覆盖）；password body `{password}` |
| 用户-角色 | `GET /system/user/{id}/roles` `PUT /system/user/{id}/roles` | 回显 `List<Long>`；保存 body 即 `List<Long>` 全量覆盖 |
| 角色 | `GET /system/role/page` `GET /system/role/list` `POST /system/role` `PUT /system/role/{id}` `DELETE /system/role/{id}` `PUT /system/role/{id}/status?status=` | page 参数 `current/size/keyword/status`；list 全量（分配下拉用） |
| 角色-权限 | `GET /system/role/{id}/perms` `PUT /system/role/{id}/perms` | 回显 `List<Long>`；保存 body 即 `List<Long>` 全量覆盖 |
| 权限 | `GET /system/perm/tree` `POST /system/perm` `PUT /system/perm/{id}` `DELETE /system/perm/{id}` `PUT /system/perm/{id}/status?status=` | tree 返回含 `children` 的菜单+按钮全量树 |
| 企业 | `GET /system/enterprise/list` | 扁平列表，用户表单/筛选下拉用 |

## 4. 前端结构与约定

### 4.1 路由（`src/router/index.ts`）
在 `MainLayout` children 追加：

```
/product      → views/Product.vue     （meta: 产品管理 / Box）
/device       → views/Device.vue      （meta: 设备管理 / Cpu）
/system/user  → views/SystemUser.vue  （meta: 用户管理）
/system/role  → views/SystemRole.vue  （meta: 角色管理）
/system/perm  → views/SystemPerm.vue  （meta: 菜单权限）
```

### 4.2 API 模块

- `src/api/product.ts` → `productApi`：`page/detail/create/update/remove` + `thingModelGet/thingModelSave`
- `src/api/device.ts` → `deviceApi`：`page/detail/create/update/remove` + `credential/regenerateCredential`
- `src/api/system.ts` → 按域命名空间：
  - `userApi`：`page/create/update/remove/switchStatus/resetPassword/roles/assignRoles`
  - `roleApi`：`page/list/create/update/remove/switchStatus/perms/assignPerms`
  - `permApi`：`tree/create/update/remove/switchStatus`
  - `enterpriseApi`：`list`

签名风格与现有 `emsApi` 一致（`Promise<PageResult<T>>` / `Promise<T>` / `Promise<number>`）。

### 4.3 类型（`src/types/models.ts` 按域追加）

- `Product`：`productId, tenantId, categoryId, productKey, productName, deviceType, authType, protocol, modelVersion, description, status, createTime, updateTime, deleted`
- `ThingModelView`：`modelId, productId, version, schemaJson, status, isCurrent`
- `Device`：`deviceId, tenantId, enterpriseId, stationId, productKey, deviceName, deviceType, parentId, path, level, sort, status, firmwareVersion, protocol, brokerNode, lastOnlineTime, lastOfflineTime, onlineSeconds, mac, ip, children?, createTime, updateTime, deleted`
- `CredentialView`：`deviceId, deviceName, deviceSecret, authStatus`
- `SysUserVO`：`userId, tenantId, enterpriseId, enterpriseName, username, realName, phone, email, status, lastLoginTime, createTime, roleIds, roleNames`
- `SysRole`：`roleId, tenantId, roleCode, roleName, dataScope, status, createTime, updateTime`
- `SysPermission`：`permId, parentId, permCode, permName, permType, resourceType, path, sort, icon, component, visible, status, remark, createTime, updateTime, children?`
- `SysEnterprise`：`enterpriseId, tenantId, parentId, path, level, enterpriseCode, enterpriseName, sort, status, children?, createTime, updateTime, deleted`

### 4.4 权限工具 `hasPermi`

新增 `src/utils/permission.ts`：

```ts
export function hasPermi(perms: string[] | undefined, required: string | string[]): boolean {
  if (required == null || (Array.isArray(required) && required.length === 0)) return true
  if (!perms) return false
  if (perms.includes('*:*:*')) return true
  const need = Array.isArray(required) ? required : [required]
  return need.some((p) => perms.includes(p))
}
```

**菜单过滤策略**（关键约定）：
- product / device 菜单**恒显**——后端这两个域没有任何权限码，仅要求登录，前端不设 `perms` 过滤；
- system 三页菜单按 `system:user:list` / `system:role:list` / `system:perm:list` 过滤；
- 按钮级用同一 `hasPermi`（如无权限隐藏「新增用户」等按钮）。

### 4.5 侧边栏（`src/layouts/MainLayout.vue`）

菜单数组改为支持 `perms` + `children`，新增「产品管理」「设备管理」两项与「系统管理」分组（`el-sub-menu`）：

```
/dashboard 设备监控 /shadow 影子 /command 指令中心 /alarm 告警中心
/product   产品管理（Box）
/device    设备管理（Cpu）
/ems/strategy 策略管理 /ems/plan 充放电计划
系统管理（Setting，el-sub-menu）
  /system/user 用户管理（system:user:list）
  /system/role 角色管理（system:role:list）
  /system/perm 菜单权限（system:perm:list）
```

模板中按 `hasPermi(authStore.permissions, m.perms)` 过滤。

### 4.6 页面结构约定（沿用现有）
- `<div class="ex-page">` → `<header class="ex-page-head">`（`ex-title` + `ex-sub` + 右侧操作按钮）
- 读数带 `<section class="ex-readout-band" style="--ro-cols:N">`（Bahnschrift 数字）
- 卡片 `<section class="ex-card">` + `ex-card-head` / `ex-card-title`
- 表格 `el-table size="small"`，数字列套 `ex-num`，空态 `el-empty`
- 分页 `el-pagination`（layout `total, sizes, prev, pager, next`）
- 操作按钮 `el-button link`
- 对话框 `el-dialog` + `el-form label-width`

## 5. 页面规格

### 5.1 产品管理 `Product.vue`

**布局**：页头（副题「产品标识 / 设备类型 / 物模型版本 · 决定设备接入协议与认证」+「新增产品」）→ 筛选条（设备类型下拉、状态下拉、关键字模糊）→ 读数带（`--ro-cols:3`：产品总数 / 启用 / 禁用，各 1 条 `pageSize=1` 轻量计数查询）→ 产品表格。

**表格列**：productKey / productName / deviceType 标签（ENERGY_CABINET 等）/ authType / modelVersion（无则 `—`）/ status 标签（启用绿/禁用灰）/ createTime（`toLocal` 格式化）/ 操作（编辑 · 物模型 · 删除）。

**新增/编辑对话框**：
- 字段：productKey（编辑时可改——后端 `ProductSaveReq` 全量覆盖，但改 key 影响已接入设备路由，前端加提示文案）、productName、deviceType（下拉枚举）、authType（SECRET/CERT）、protocol（默认 MQTT）、status（启用/禁用）、description
- 校验：productKey/productName 必填；productKey max64；deviceType 必填

**物模型抽屉**（点「物模型」打开，`el-drawer`）：
- 加载 `thingModelGet`：存在 → 展示当前版本（version + status 标签[0草稿/1已发布/2已废弃] + isCurrent 标记）与 JSON 编辑器（textarea 预填 `schemaJson`）；NOT_FOUND → 空态「尚未发布物模型」+ 空模板提示
- 工具行：版本号输入（默认当前版本，改号=发新版本，同号=覆盖）+ 「格式化 JSON」 + 「校验 JSON」 + 「发布」
- 发布：`thingModelSave(productId, {version, schemaJson})` → 成功提示后重载
- 校验失败/JSON 语法错 → `ElMessage.error` 阻止提交

**删除**：`ElMessageBox.confirm` 确认 → `productRemove` → 重载列表。

### 5.2 设备管理 `Device.vue`

**布局**：页头（副题「设备树统一建模 · 状态字典 0未注册~5封禁 · 凭据仅查询时脱敏展示」+「新增设备」）→ 筛选条（设备类型、状态、关键字设备名、电站 ID 数字输入——**不做产品筛选**，后端 `DeviceQuery` 无该字段）→ 读数带（`--ro-cols:4`：设备总数 / 在线(status=3) / 离线(status=2) / 禁用(status=4)，各 1 条 `pageSize=1` 计数）→ 设备表格。

**表格列**：deviceId（`ex-num`）/ deviceName / productKey / deviceType / status 标签（0未注册灰、1未激活蓝、2离线灰、3在线绿、4禁用红、5封禁黑）/ stationId / lastOnlineTime（`toLocal`）/ createTime / 操作（详情 · 编辑 · 删除）。

**详情抽屉**（点行 / 点「详情」打开，只读 + 凭据管理）：
- 基本信息 `el-descriptions`：deviceId、deviceName、productKey、deviceType、status 标签、stationId、enterpriseId、parentId、path、level、firmwareVersion、protocol、mac、ip、lastOnlineTime、lastOfflineTime、onlineSeconds（秒→天时分）、createTime
- 凭据卡：`credential(deviceId)` → deviceSecret **脱敏**展示 + authStatus 标签（正常绿/吊销红）+ 「重新生成」按钮（confirm 后 `regenerateCredential` → 明文展示一次 + 「复制」按钮，复制后清空明文）

**编辑入口**：表格「编辑」打开与新增共用的对话框（`deviceName/productKey/deviceType` 不可改——后端 `DeviceUpdateReq` 不支持这些字段；`stationId/enterpriseId/firmwareVersion/mac/ip/sort/status` 可改），保存走 `deviceUpdate`。

**新增对话框**：
- 字段：deviceName（必填，禁 `_`/`&`，前端正则校验 + 提示）、产品下拉（`productApi.page({pageNum:1,pageSize:100})` → 选中自动带出 productKey + deviceType）、enterprise 下拉（`enterpriseApi.list`）、stationId 数字输入、firmwareVersion、mac、ip、status（默认 0 未注册）
- 提交：`deviceCreate` 返回 `Long deviceId` → 提示「创建成功，设备 ID=xxx，凭据已生成，请立即在详情中查看/复制」+ 重载
- 创建后密钥明文只在重生成时再见——提示引导用户打开详情抽屉查看凭据

**删除**：confirm（提示「将级联删除该设备整棵子树并吊销凭据」）→ `deviceRemove` → 重载。

### 5.3 用户管理 `SystemUser.vue`

**布局**：页头（副题「RBAC 用户 · 分配角色决定可见菜单与操作权限」+「新增用户」）→ 筛选条（关键字 username/realName 模糊、状态、所属企业下拉 `enterpriseApi.list`）→ 用户表格。

**表格列**：username / realName / phone / status 标签（0禁用红、1启用绿、2锁定灰）/ roleNames 标签组 / enterpriseName / lastLoginTime（`toLocal`）/ createTime / 操作。

**新增/编辑对话框**：
- 字段：username（正则 `^[a-zA-Z0-9_.-]+$`）、realName、phone、email、所属企业下拉、status、**密码**（创建必填 6~64；编辑留空=不改，placeholder 说明）、roleIds 多选（`roleApi.list`，清空=不传）
- 编辑时预填：先 `userDetail`（拿 roleIds/roleNames），不必单独调 roles 接口

**操作**：
- 分配角色：对话框 `userRoles(id)` 回显 → 多选 → `assignRoles(id, roleIds)` 全量覆盖
- 重置密码：对话框单输入 `password` → `userResetPassword(id, {password})`
- 启停：`switchStatus(id, 0|1)`；删除：confirm
- 后端规则提示：超管（userId=1）与当前登录账号不可删/禁——前端对 `userId===1` 或 `=== authStore.user.userId` 禁用操作按钮

### 5.4 角色管理 `SystemRole.vue`

**布局**：页头（副题「角色承载权限集合 · dataScope 决定数据可见范围」+「新增角色」）→ 筛选条（关键字 roleCode/roleName、状态）→ 角色表格。

**表格列**：roleCode / roleName / dataScope 标签（1本人、2本企业、3本租户、4全部）/ status / createTime / 操作（编辑 · 授权权限 · 启停 · 删除）。

**新增/编辑对话框**：roleCode（正则 `^[A-Za-z][A-Za-z0-9_]*$`）、roleName、dataScope 下拉（1~4）、status。

**授权权限对话框**：
- `permApi.tree()` 全量树 → `el-tree show-checkbox`（`node-key="permId"`，`props:{label:'permName',children:'children'}`，`default-expand-all`）
- 回显：`rolePerms(id)` 返回 `List<Long>` 平铺 id → 直接设为 `setCheckedKeys`（el-tree 按子级自动推导父级半选/全选态）
- 保存：**勾选 + 半选父节点合并整包传**——`assignPerms(id, [...getCheckedKeys(), ...getHalfCheckedKeys()])`，与后端 `role_permission` 平铺存 id 一致，避免父节点「授权但子级未全选」被全量覆盖丢行

**删除**：confirm；内置 SUPER_ADMIN（roleId=1）与已分配用户的角色不可删——前端对 roleId===1 禁用操作。

### 5.5 菜单权限管理 `SystemPerm.vue`

**布局**：页头（副题「菜单 / 按钮 / 数据 权限树 · 决定侧边栏可见性与操作按钮」+「新增权限」）→ 树形表格。

**树形表格**：`el-table :data="tree" row-key="permId" :tree-props="{children:'children'}" default-expand-all`，列：permName / permCode / permType 标签（1菜单、2按钮、3数据）/ path / icon / visible（显示/隐藏）/ status / sort（`ex-num`）/ 操作（新增子 · 编辑 · 启停 · 删除）。

**新增/编辑对话框**：
- parentId：父级树选择（`el-tree-select` 或 cascader，顶级=0）
- permType radio（1菜单 2按钮 3数据）、permCode（全局唯一提示）、permName、resourceType（DEVICE/STRATEGY/ALARM/STATION，可选）、path、icon、component、visible、status、sort、remark
- 删除：confirm；有子节点后端拒绝 → 前端对有 `children` 的节点禁用删除按钮并提示

## 6. 关键交互与错误处理

- 所有写操作失败统一 `ElMessage.error(e.message)`（http.ts 已归一化）；成功统一 `ElMessage.success` 轻提示。
- 删除/重生成凭据/重置密码/启停等破坏性或不可逆动作，一律先 `ElMessageBox.confirm`。
- 凭据明文只在创建/重生成时出现一次——页面上复制后即清空展示。
- 计数读数带（产品/设备）用 `pageSize=1` 轻量查询，避免为计数引入新后端接口。
- 加载态：表格 `v-loading`；对话框提交按钮 `:loading`。
- 权限不足的按钮直接不渲染（`v-if="hasPermi(...)"`），不做置灰——保持界面干净。

## 7. 阶段二：策略 / 计划模块人性化优化（建议，不在本期实现）

基于对 `EmsStrategy.vue` / `EmsPlan.vue` 的读码评估，痛点与建议按优先级排列。**本期不实现**，待用户确认后作为独立迭代。

| 优先级 | 优化 | 现状痛点 |
|---|---|---|
| P0 | 策略配置结构化编辑：按策略类型渲染表单（峰谷套利：充/放窗口表+功率限制；需量：阈值；SOC 约束：上下限；时间策略：时段），保留「切换 JSON 模式」兜底 | 现在 `config` 是裸 JSON 文本框，无校验无提示，`{"chargeWindows":[...]}` 只能手写 |
| P1 | 电站名称化：新增/编辑/列表显示电站名（电站下拉），替代裸 `stationId` 数字 | 策略、计划页电站都是裸 ID |
| P1 | 计划页内联「生成计划」表单：日期选择器 + 策略下拉 + 电站选择 | 生成计划只能去策略页点「生成计划」，日期写死今天 |
| P1 | 复制策略 | 高频操作缺失 |
| P2 | 一键生成一周计划；时间格式化（`toLocal`）；空态/未配置电价提示 | 列表时间 ISO 原样、电价底纹缺失无提示 |
| P2 | 下发后执行追踪：轮询计划状态 + 若 TDengine 可得则 ECharts 叠加实际曲线 | 下发后无任何执行反馈 |

## 8. 测试

- 复用现有前端测试脚手架（`vitest` + `src/**/__tests__`）：
  - `permission.spec.ts`：`hasPermi` 的 `*:*:*` 恒真、多权限 OR、空 required 恒真、undefined perms 恒假
  - `http` 层不新增（复用现有拦截器单测）
  - 纯函数单测：状态字典映射（device status / dataScope / permType / thingModel status）抽为 `src/utils/dicts.ts` 并单测
- 手工冒烟（`npm run dev` + 已跑后端）：
  - admin 登录 → 全菜单可见；建产品 → 发物模型 → 建设备（选该产品）→ 详情看脱敏凭据 → 重生成看明文 → 删除
  - 建角色（授权权限树）→ 建用户（分配角色）→ 用该用户登录 → 系统管理菜单隐藏、产品/设备可见
  - RBAC 页面按钮随权限码显隐

## 9. 涉及文件清单（本期）

```
frontend/src/router/index.ts                 （+5 路由）
frontend/src/layouts/MainLayout.vue          （菜单分组 + perms 过滤）
frontend/src/utils/permission.ts             （新，hasPermi）
frontend/src/utils/dicts.ts                  （新，状态字典映射）
frontend/src/types/models.ts                 （+Product/ThingModel/Device/Credential/SysUser/SysRole/SysPermission/SysEnterprise）
frontend/src/api/product.ts                  （新）
frontend/src/api/device.ts                   （新）
frontend/src/api/system.ts                   （新）
frontend/src/views/Product.vue               （新）
frontend/src/views/Device.vue                （新）
frontend/src/views/SystemUser.vue            （新）
frontend/src/views/SystemRole.vue            （新）
frontend/src/views/SystemPerm.vue            （新）
frontend/src/utils/__tests__/permission.spec.ts （新）
frontend/src/utils/__tests__/dicts.spec.ts      （新）
```
