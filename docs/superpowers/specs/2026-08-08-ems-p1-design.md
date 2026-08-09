# EMS P1 三件套设计：电站名称化 / 计划页内联生成 / 复制策略

- 日期：2026-08-09
- 依据：`docs/superpowers/specs/2026-08-08-ems-ux-evaluation.md` §2 P1 行（三小项后端均已就绪，纯前端活）
- 迭代流：brainstorming → spec → writing-plans → subagent-driven 执行 → 冒烟 → push

## 1. 背景与范围

UX 评估 P1 三项痛点与建议：

| 项 | 现状 | 目标 |
|----|------|------|
| 电站名称化 | 策略/计划页电站是裸数字 ID：策略对话框 `el-input` 占位「电站 ID」、策略列表**无电站列**、计划页副题 `电站 ${stationId} · 策略 ${strategyId}`、计划列表裸 ID | 表单用电站下拉（label `stationName`，value `stationId`）；列表/副题显示名称 |
| 计划页内联生成 | 生成入口只在策略页行操作，且硬编码 planDate=今天 | 计划页内「生成计划」弹窗：日期 + 电站 + 策略下拉（按站过滤），调 `planGenerate` |
| 复制策略 | 行操作只有 编辑/生成计划/启停/删除 | 列表加「复制」→ 预填新增弹窗（名称加「副本」），纯前端 |

**用户决策（brainstorming 澄清）**：
- 计划页生成弹窗策略下拉：**可空自动 + 仅启用**（候选 = 该站 status=1 策略；不选则后端自动挑启用中优先级最高）
- 电站名称化**仅覆盖策略/计划页**；设备页裸 stationId 记为 SAFE-TO-DEFER
- 策略页行操作「生成计划」**保留**（单条策略一键生成今天计划的快捷入口，不冲突）

**非目标**：设备页名称化、策略页生成入口改造、后端任何改动（全部后端已就绪）。

## 2. 现状探索结论

- **无 stationApi / Station 类型**：`frontend/src/api/` 九个模块（command/auth/http/device/product/shadow/system/ems/alarm），无 station；`models.ts` 只有各表 `stationId` 字段。
- **后端就绪**：
  - `GET /api/station/page` → `PageResult<Station>`（网关 `Path=/api/station/**` + StripPrefix=1，前端路径不变）。
  - `StationQuery` 分页参数是 **`pageNum`**（非 EMS 的 `pageNo`）；支持 `enterpriseId/keyword/status/gridType` 过滤。
  - `Station` 实体：`stationId(Long→字符串) / enterpriseId / stationCode / stationName / address / longitude / latitude / installCapacity / pcsCapacity / batteryCapacity / gridType / status(0停运 1运行)`。
  - `strategyPage` 支持 `stationId` 过滤 → 策略下拉按站过滤已具备。
  - `planGenerate({stationId, strategyId?, planDate})`：`strategyId` 可空 → 后端 `resolveStrategy` 自动挑该站 status=1 中优先级最高；同站同日期已有计划 → CONFLICT「该电站该日期已存在计划，请勿重复生成」。
- **名称化现状**：EmsStrategy 列表无电站列、对话框裸 ID input；EmsPlan 副题/列表裸 ID。
- **潜藏缺口**：EmsStrategy 对话框无 `watch(editing.stationId)` 重载包络——el-input 时代切换电站不是有意识操作，S1 竞态守卫（loadEnvelope 内校验当前站）已在但无触发源；改为下拉后切换成为首类动作，须补 watch。

## 3. 架构与组件

```
新增  types/models.ts          Station 接口
新增  api/station.ts           stationApi（与 ems.ts 同款）
新增  utils/stationDict.ts     电站列表 memo 缓存 + 名称纯函数 + 测试 reset
改    views/EmsStrategy.vue    电站下拉 / 电站列 / 复制行操作 / envelope watch
改    views/EmsPlan.vue        站/策略名映射、副题与列表名称化、内联生成弹窗
新增  utils/stationDict.spec.ts vitest
```

**电站列表共享方案（已选 A）**：模块级缓存 `utils/stationDict.ts`，两页共享，路由切换不重复请求；纯函数可 vitest；与现有 `utils/strategyConfig.ts`/`dicts.ts` 惯例一致。否决：每页自拉（重复请求、无测试价值）、Pinia store（对两消费者过重，repo 无 store 先例）。

## 4. 分项设计

### 4.1 电站名称化

**`types/models.ts`** 新增：

```ts
export interface Station {
  stationId: string            // Long → 字符串序列化（JacksonConfig ToStringSerializer，雪花同款）
  enterpriseId?: string | null
  stationCode?: string | null
  stationName: string
  address?: string | null
  gridType?: string | null
  status?: number              // 0停运 1运行
}
```

**`api/station.ts`** 新增（⚠️ `pageNum` 不是 `pageNo`）：

```ts
import http from './http'
import type { PageResult, Station } from '@/types/models'

/** 电站资产 API（网关路由 /api/station/** → energy-station，StripPrefix=1） */
export const stationApi = {
  /** GET /api/station/page 分页查询（后端 StationQuery 用 pageNum，勿与 EMS pageNo 混淆） */
  stationPage(params: Record<string, unknown>): Promise<PageResult<Station>> {
    return http.get('/api/station/page', { params })
  },
}
```

**`utils/stationDict.ts`**（方案 A memo 缓存）：

```ts
import { stationApi } from '@/api/station'
import type { Station } from '@/types/models'

let cached: Station[] | null = null

/** 电站列表（模块级缓存，首次拉取后复用；force=true 强制重拉） */
export async function loadStations(force = false): Promise<Station[]> {
  if (cached && !force) return cached
  const page = await stationApi.stationPage({ pageNum: 1, pageSize: 100 })
  cached = page.records
  return cached
}

/** 测试用：清空缓存 */
export function _resetStationCache(): void { cached = null }

/** 名称解析：查不到回退裸 id，绝不空白 */
export function stationName(id: string | number | null | undefined, stations: Station[]): string {
  const key = String(id ?? '')
  if (!key) return ''
  return stations.find((s) => String(s.stationId) === key)?.stationName ?? key
}
```

**EmsStrategy.vue**：
- 列表在类型列后新增「电站」列：`{{ stationName(row.stationId, stations) }}`（`stations` 为页面持有的 `loadStations()` 结果）。
- 对话框「电站 ID」`el-input` → `el-select`（`filterable` 可搜索；option label=`stationName`、value=`stationId`）。`onMounted` 与每次开弹窗前 `loadStations()` 填充；编辑回填选中项。
- 补 `watch(() => editing.value.stationId, () => void loadEnvelope())`：下拉切换电站即重载包络（S1 竞态守卫已处理异步过期）。

### 4.2 计划页内联生成

**EmsPlan.vue** 页头「下发计划」旁新增「生成计划」按钮 → 弹窗：

| 字段 | 控件 | 语义 |
|------|------|------|
| 计划日期 | `el-date-picker`（`value-format="YYYY-MM-DD"`） | 默认今天，必填 |
| 电站 | `el-select`（`loadStations()`） | 必填；选中后拉该站策略候选 |
| 策略 | `el-select` 可空，placeholder「自动选择（启用中优先级最高）」 | 候选 = `strategyPage({pageNo:1, pageSize:50, stationId, status:1})`；电站变化清空已选 |

- 未选电站时策略下拉禁用（placeholder「请先选择电站」）。
- 确定 → `planGenerate({stationId, strategyId?, planDate})` → `ElMessage.success('计划已生成')` + 关弹窗 + `load()`（列表按计划日期倒序，新计划自动落首行选中展示波形）。
- 错误透传后端文案（重复生成 CONFLICT、未配置安全约束等），不吞错。

### 4.3 复制策略

EmsStrategy.vue 行操作「编辑」后新增「复制」按钮：

```ts
function copyStrategy(row: EmsStrategy) {
  editing.value = {
    stationId: row.stationId,
    strategyName: `${row.strategyName} 副本`,
    strategyType: row.strategyType,
    config: row.config,
    priority: row.priority,
  }
  isEdit.value = false
  dialogVisible.value = true
  void loadEnvelope()
}
```

- 纯前端：create 端点强制 status=0/version=1 → 副本天然草稿；config 原样带入 → 可解析则结构化表单回显、不可解析则 JSON 模式保留文本（S1 模式进入规则原样生效）。
- 名称无唯一约束，「副本」后缀重复复制可出现同名，用户在弹窗内可改。
- 保存走既有 `save()`：仅提交 DTO 字段（S1 修复的契约，勿整行直发）。

## 5. 数据流

- **策略页**：`onMounted(load)` 并行拉策略列表 + `loadStations()`；开弹窗（新增/编辑/复制）复用同一份 stations 填充下拉；stationId 变化 → `loadEnvelope()` 重载该站包络。
- **计划页**：`onMounted` 并行拉计划列表、`loadStations()`、`strategyPage({pageNo:1,pageSize:100})` → 建 `Map<id,name>` 两张；生成弹窗电站变化 → 拉该站启用策略候选。
- **名称回退**：任何 id 在 map/列表查不到 → 显示裸 id，绝不空白。

## 6. 错误处理

- `loadStations()` 失败：页面 catch → `ElMessage.error` 一次性提示；名称回退裸 id 不受影响；下拉无选项（用户可重试/刷新）。不与保存路径耦合。
- 生成失败：`planGenerate` 的 BusinessException 文案（含 CONFLICT 重复生成）经 `toFriendlyError` 透传到 toast。
- 复制/编辑/删除沿用既有保存路径的错误处理（success/error toast）。

## 7. 测试与验收

**vitest**（`utils/stationDict.spec.ts`，mock `@/api/station`，仿 `strategyConfig.spec.ts`）：
1. `loadStations` 首次拉取后二次调用不重复请求（mock 计数 = 1，返回同引用）。
2. `loadStations(true)` 强制重拉（mock 计数 = 2）。
3. `_resetStationCache` 后重新拉取。
4. `stationName`：已知 id → 返回名称；未知 id → 回退原 id；`undefined/null` → 空串。

**gate**：`vue-tsc --noEmit` 0 错 + `vitest run` 全绿。

**冒烟清单（P1 新增，沿用 S1 §3.5 脚手架）**：
1. 策略页新建：电站下拉显示名称（非裸 ID），选站保存后列表出现「电站」名列显示名称。
2. 复制：点「复制」→ 弹窗为新增态（标题「新增策略」）、名称含「副本」、电站/类型/config 保留；保存后新行状态草稿、原策略未动。
3. 计划页：副题与列表电站/策略列均显示名称。
4. 计划页生成：自动策略路径（不选策略）与显式策略路径各一次 → success + 列表刷新。
5. 同站同日重复生成：二次生成 toast 显示后端 CONFLICT 文案。
6. 策略下拉按站过滤：切电站后候选仅该站启用策略。
7. 编辑策略回填：电站下拉选中项为原站名称。

## 8. 非目标 / SAFE-TO-DEFER

- 设备页（Device.vue）电站裸 stationId（筛选输入 + 列表列 + 详情）——本次不覆盖，记为 SAFE-TO-DEFER（stationApi 就绪后改造近免费）。
- 策略页行操作「生成计划」保留现状（硬编码今天、带 strategyId），不加日期选择器。
- `loadStations` pageSize=100：电站超百时超出的名称回退裸 id（当前站点数远小于百，可接受；如需要可分页/搜索增强，另行迭代）。
