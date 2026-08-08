# EnergyX 策略/计划 未实现类型标注与禁用（S2）设计

> 2026-08-08。本设计将 `2026-08-08-ems-ux-evaluation.md` 的 P0 项 **S2（假可用）** 落为一期**纯前端**迭代，附计划页空态兜底（P4 相关）。
>
> 迭代边界：**纯前端**，不触碰后端 API 契约。

## 1. 背景与问题

后端 `PlanGenerator.java:34` 仅实现 `PEAK_VALLEY`，其余 4 种策略类型（DEMAND / DR / SOC_CTRL / TIME）生成时直接 `return List.of()` → 0 点计划。但策略页类型下拉开放全部 5 种，「生成计划」对 status=1 的任意类型都可用（`EmsStrategy.vue:135`），导致用户为不支持的策略类型生成计划 → 计划页波形空白，全程无提示，用户以为生成了可用计划。

**本次目标**：让「生成计划」对未支持类型**不可用但可解释**，并让历史遗留的 0 点计划在计划页有明确空态，而非无提示的空白波形。

## 2. 方案决策（支持类型集合的机制）

| 方案 | 做法 | 权衡 |
|---|---|---|
| **A（采纳）** | 前端硬编码 `STRATEGY_GENERATABLE_TYPES = ['PEAK_VALLEY']` + `isStrategyGeneratable()` 谓词（放 `dicts.ts`），注释指向 `PlanGenerator.java` | 零后端改动、纯函数可单测；后端新增类型时手动同步该常量（当前仅 1 种，成本可忽略） |
| B | 后端暴露 `GET /api/ems/strategy/supported-types` 能力接口 | 永不漂移，但动后端 + 网关契约 + 拉取时序，对本迭代纯前端边界是过度设计 |
| C | 反应式：生成后 points 为空再提示 | 不能阻止点击，用户事后才知，UX 差 |

## 3. 设计

### 3.1 支持类型集合 `frontend/src/utils/dicts.ts`

新增：

```ts
/** 可生成调度计划的策略类型（与后端 PlanGenerator.java 支持集合对齐；后端新增支持时同步此数组） */
export const STRATEGY_GENERATABLE_TYPES: string[] = ['PEAK_VALLEY']
export function isStrategyGeneratable(type?: string): boolean {
  return !!type && STRATEGY_GENERATABLE_TYPES.includes(type)
}
```

放置理由：与现有导出常量 `deviceTypeOptions` 同构——`dicts.ts` 已是策略/字典常量的归宿，导出常量 + 派生谓词的既有模式。

### 3.2 策略页 `frontend/src/views/EmsStrategy.vue`

- **「生成计划」按钮**（现 `:135` `v-if="row.status === 1"`）：保留可见但加 `:disabled="!isStrategyGeneratable(row.strategyType)"`，外裹 `el-tooltip`，禁用时提示「该策略类型暂不支持生成计划（后端仅实现峰谷套利）」——**按钮保留可见但禁用**，让用户明白「为什么点不了」，比直接隐藏更可解释。
- **`generatePlan` 防御性 guard**：函数开头若非生成类型直接 `ElMessage.warning(...)` 返回（防绕过 UI 的调用路径）。
- **类型下拉**（现 `:164-170`）：非生成类型 option 的 label 追加「（暂不支持生成）」。
- **`save()`**：保存非生成类型时，保存前 `ElMessage.warning('当前仅峰谷套利可生成计划，其余类型可保存但不可生成')`，**照常保存不阻断**（允许用户为 DEMAND/DR 等预配置，待后端支持）。

### 3.3 计划页 `frontend/src/views/EmsPlan.vue` 空态兜底

- `selectPlan` 拉取点序列后若 `pts.length === 0`：波形卡渲染 `el-empty`「该计划无点序列——所选策略类型暂不支持生成」；读数带保留（pointCount = 0），不渲染空白波形图。
- 判定用 `const emptyPoints = computed(() => points.value.length === 0)` 控制。

### 3.4 测试

- `frontend/src/utils/__tests__/dicts.spec.ts` 新增 `isStrategyGeneratable` 用例：
  - `PEAK_VALLEY` → `true`
  - `DEMAND` / `DR` / `SOC_CTRL` / `TIME` → `false`
  - `undefined` / `''` → `false`
- 全量 `vue-tsc` 0 错 + vitest 全绿。
- 手工冒烟清单：
  1. 新建/编辑一个 DEMAND 策略保存 → 出现保存 warning，但保存成功。
  2. 该行「生成计划」按钮禁用，悬停有 tooltip。
  3. PEAK_VALLEY 策略「生成计划」按钮可用，行为与现状一致。
  4. 打开历史 0 点计划 → 计划页显示空态而非空白波形。

## 4. 影响面与风险

- 涉及文件：`frontend/src/utils/dicts.ts`、`frontend/src/views/EmsStrategy.vue`、`frontend/src/views/EmsPlan.vue`、`frontend/src/utils/__tests__/dicts.spec.ts`（2 改 + 1 测）。
- 风险：常量与后端能力漂移 → 用注释强制同步点；后端新增支持类型时需同步更新本常量。
- 明确**不做**（YAGNI）：方案 B 能力接口、S1 结构化表单、P1 电站名称化 / 计划页生成入口 / 复制策略、P2 各项——均留待后续独立迭代。

## 5. 验收标准

1. 非 `PEAK_VALLEY` 策略：生成按钮禁用 + tooltip 可解释；保存有 warning 但不阻断。
2. `PEAK_VALLEY` 策略：生成按钮可用，行为与现状一致。
3. 0 点历史计划：计划页显示空态提示，读数带保留。
4. `vue-tsc` 0 错；vitest 全绿（含新增单测）。
