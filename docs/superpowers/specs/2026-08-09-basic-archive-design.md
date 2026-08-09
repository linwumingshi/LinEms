# 子项目 A：基础档案管理（单位管理 + 电站管理）

**日期：** 2026-08-09
**关联需求：** 新功能清单 ID 1「基础档案管理」
**批次拆解：** 本 spec 仅覆盖子项目 A。子项目 B「设备数据中心」（TSL 解析 + 最新值/历史值）与子项目 C「IoT 联动」（影子/指令中心动态 TSL）另立 spec，不在本文件范围。

## 1. 背景与现状

后端已完整，前端缺失：

| 模块 | 后端现状 | 前端现状 |
|---|---|---|
| 单位 | `SysEnterpriseController`（`/system/enterprise`，网关 StripPrefix=1）：tree / list / detail / create / update / delete / changeStatus；权限 `system:enterprise:list/add/edit/remove`（V3 种子） | 仅 `enterpriseApi.list()`；无页面/路由/导航 |
| 电站 | `StationController`（`/station`，网关 StripPrefix=1）：page / detail / create / update / delete（逻辑删除）；无 @PreAuthorize | 仅 `stationApi.stationPage()`；无页面/路由/导航 |

## 2. 设计决策（已与用户确认）

1. **单位管理页 = 左树右详情**（左侧单位树 + 右侧选中单位详情/操作）；电站管理页 = 标准表格 CRUD。
2. **导航 = 「基础档案」菜单组**，子项 单位管理 `/archive/enterprise`、电站管理 `/archive/station`；位于「充放电计划」之后、「系统管理」之前。
3. **电站页按钮不做权限门控**（后端无 station:* 权限码，与后端行为一致）；单位页按钮门控 `system:enterprise:add/edit/remove`。

## 3. 接口契约（前端封装，不改后端）

### 3.1 单位 enterpriseApi（修改 `frontend/src/api/system.ts`）

| 函数 | 方法与路径 |
|---|---|
| tree() | GET /api/system/enterprise/tree → SysEnterprise[]（树，children） |
| list() | GET /api/system/enterprise/list（已有，保留） |
| detail(id) | GET /api/system/enterprise/{id} |
| create(body) | POST /api/system/enterprise → string |
| update(id, body) | PUT /api/system/enterprise/{id} |
| remove(id) | DELETE /api/system/enterprise/{id} |
| switchStatus(id, status) | PUT /api/system/enterprise/{id}/status?status= |

`SysEnterpriseSaveReq`：`enterpriseCode`（必填，max64）、`enterpriseName`（必填，max128）、`parentId`、`sort`、`status`。

**后端语义（前端须知情）：** 编码唯一（重复 → CONFLICT「单位编码已存在」）；parentId=0/空 = 顶级（level 1）；父级不能是自身或其子树（成环校验）；删除前置：有子单位 → CONFLICT「存在子单位，请先删除子单位」，单位下有用户 → CONFLICT「单位下存在用户，无法删除」；status 0 禁用 1 启用（默认 1）；sort 默认 0。

### 3.2 电站 stationApi（修改 `frontend/src/api/station.ts`）

| 函数 | 方法与路径 |
|---|---|
| stationPage(params) | GET /api/station/page（已有；pageNum/pageSize/enterpriseId/keyword/status/gridType） |
| create(body) | POST /api/station → string |
| detail(id) | GET /api/station/{id} |
| update(id, body) | PUT /api/station/{id} |
| remove(id) | DELETE /api/station/{id} |

`StationSaveReq`：`stationCode`（必填 max64）、`stationName`（必填 max128）、`enterpriseId`（必填）、`address`（max256）、`longitude`、`latitude`、`installCapacity`（kWh）、`pcsCapacity`（kW）、`batteryCapacity`（kWh）、`gridType`（max16）、`status`（默认 1 运行）。

**后端语义：** 电站编码唯一索引（含软删行，删除后不可复用同名 code）；删除为逻辑删除。

### 3.3 类型（修改 `frontend/src/types/models.ts`）

- `Station` 补字段：`longitude`、`latitude`、`installCapacity`、`pcsCapacity`、`batteryCapacity`、`tenantId`、`createTime`、`updateTime`（与后端实体类型对应；stationId 保持 string）。
- 新增 `SysEnterpriseSaveReq`、`StationSaveReq` 接口。

## 4. 页面设计

### 4.1 单位管理 `frontend/src/views/Enterprise.vue`（左树右详情）

- **左栏**（宽约 260px）：顶部「＋新增根单位」按钮（门控 system:enterprise:add）+ `el-tree` 渲染 `enterpriseApi.tree()`，节点显示 `enterpriseName`；默认展开第一层。
- **右栏**：
  - 未选中节点 → 空态提示「请选择左侧单位」。
  - 选中 → `el-descriptions`（单位编码 / 单位名称 / 上级单位 / 排序 / 状态 / 创建时间 / 更新时间）+ 操作按钮：编辑、新增子单位、停用|启用（门控 system:enterprise:edit）、删除（门控 system:enterprise:remove）。
- **新增/编辑 dialog**（字段 = SysEnterpriseSaveReq，11-b 范式）：
  - 新增根单位：上级单位隐藏。
  - 新增子单位：上级单位只读显示当前选中节点名（parentId 固定为该节点）。
  - 编辑：上级单位可选（下拉排除自身及其子树，后端环校验兜底）。
  - 必填红星：单位编码、单位名称；错误内联 `:error`，重开弹窗清空（watch 单 getter 数组形式）。
- **交互**：
  - 树节点点击 → 右侧渲染该节点；树 reload 后按 enterpriseId 重新选中（用 `findEnterpriseNode`），避免绑定陈旧引用。
  - 删除：客户端预检该节点 children 非空 → `ElMessage.warning('存在子单位，请先删除子单位')`；否则 confirm → `remove()`；后端「单位下存在用户」由全局拦截器 toast。
  - 停用/启用 → `switchStatus(id, 1|0)`，成功后刷新树与右侧。
  - 所有写操作成功 → 刷新树；若当前选中被删，右侧回空态。

### 4.2 电站管理 `frontend/src/views/Station.vue`（标准表格 CRUD）

- **过滤卡片**：关键字（名称/编码模糊）、所属单位下拉（`enterpriseApi.list()` 平铺）、状态、电网类型。
- **表格列**：电站编码 / 电站名称 / 所属单位（名，缺失显示 `—`）/ 装机容量 kWh / PCS 容量 kW / 电池容量 kWh / 电网类型 / 状态（运行 tag / 停运 tag）/ 操作（编辑、删除）。
- **新增/编辑 dialog**（字段 = StationSaveReq）：
  - stationCode*、stationName*、enterpriseId*（下拉，`:label` 用单位名）、address、longitude、latitude、installCapacity、pcsCapacity、batteryCapacity、gridType（下拉：工商业/园区/电网侧）、status。
  - stationId 由后端雪花自增，不进表单。
  - 11-b 同上。
- **交互**：分页 pageNum/pageSize + 过滤参数透传；筛选/翻页 → 重新加载；删除 confirm → remove；后端 CONFLICT（编码重复等）由拦截器 toast。

### 4.3 共享工具

- `frontend/src/utils/enterpriseTree.ts`：`findEnterpriseNode(tree: SysEnterprise[], id: string | null | undefined): SysEnterprise | null`（按 enterpriseId 深搜返回节点或 null）。
- `frontend/src/utils/dicts.ts` 追加 `GRID_TYPE_OPTIONS = ['工商业', '园区', '电网侧']`。

## 5. 全局约束（沿用现有页面范式）

- **11-b 内联校验**：必填红星（el-form-item required），错误 `:error` 红字，不用系统 toast；域名前置条件（如单位有子节点）→ `ElMessage.warning`；后端业务错误 → 全局拦截器 toast。
- **门控**：`hasPermi(permissions, codes)`（`utils/permission.ts`；含 `*:*:*` 超管恒真）。
- **布局令牌**：`ex-page-head` / `ex-filter-card` / `ex-card` 等；页面结构与 Product/Device/SystemUser 同构。
- **id 均为 string**（雪花 Long → 字符串约定）。
- **提交红线**：不 `git add -A`；本机 `BrokerProperties.java` / `vite.config.ts` 永不提交。

## 6. 文件结构

- Modify `frontend/src/api/system.ts` — enterpriseApi 补 tree/detail/create/update/remove/switchStatus。
- Modify `frontend/src/api/station.ts` — stationApi 补 create/detail/update/remove。
- Modify `frontend/src/types/models.ts` — Station 补字段 + 两个 SaveReq。
- Modify `frontend/src/utils/dicts.ts` — 追加 GRID_TYPE_OPTIONS。
- Create `frontend/src/utils/enterpriseTree.ts` — findEnterpriseNode。
- Create `frontend/src/utils/__tests__/enterpriseTree.spec.ts` — vitest。
- Modify `frontend/src/router/index.ts` — 2 路由。
- Modify `frontend/src/layouts/MainLayout.vue` — 基础档案组。
- Create `frontend/src/views/Enterprise.vue`。
- Create `frontend/src/views/Station.vue`。

## 7. 测试

- **vitest**：新增 `enterpriseTree.spec.ts`（findEnterpriseNode 覆盖：命中/未命中/根/多级/空树/null id）；full suite 绿（现有 86 tests 不回归）。
- **vue-tsc** EXIT 0。
- **浏览器冒烟**（subagent 执行）：登录 admin/admin123 → 侧栏见「基础档案」组 → 单位管理：新增根单位 → 新增子单位 → 编辑 → 停用/启用 → 删除（先删子再删根，验证「存在子单位」warning 路径）→ 电站管理：新增电站（选单位）→ 编辑 → 删除 → EmsStrategy / EmsPlan 电站下拉可见新电站。

## 8. 明确不做（YAGNI）

- 电站页按钮门控（决策 3；如需 → 另排后端 @PreAuthorize + 权限种子）。
- 设备归属调整 / device 关联。
- 站点坐标地图可视化（仅存经纬度字段）。
- 任何后端改动。