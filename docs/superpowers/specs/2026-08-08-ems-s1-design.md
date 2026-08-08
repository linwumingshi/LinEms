# EnergyX 策略配置 结构化 JSON 表单（S1）设计

> 2026-08-08。本设计将 `2026-08-08-ems-ux-evaluation.md` 的 P0 项 **S1（配置裸 JSON 无校验）** 落为一期**纯前端**迭代。
>
> 迭代边界：**纯前端**，不触碰后端 API 契约。

## 1. 背景与问题

策略弹窗的「配置 JSON」是裸 `el-input type="textarea"`（`EmsStrategy.vue:194-196`），无结构化编辑、无保存前校验：

- **保存时零校验**：`EmsStrategySaveReq.config` 仅 `@NotBlank`（`EmsStrategySaveReq.java:22`），controller/service 均不解析 JSON。任何字符串都能保存成功。
- **错误全部后置到「生成计划」**：JSON 语法错或结构化错（缺 start/end、powerLimit 超限）要到 `PlanGenerator.generate()` 才由 `PlanGenerator.java:66` 抛 `策略配置解析失败: …`，用户在配置页全程无反馈。更糟的是**部分结构化错静默吞掉**：`start >= end` 的窗口循环不执行 → 0 点，不报错（`PlanGenerator.java:45,55`）。
- **`socRange` 是死字段**：`PlanInput.java:10` 注释与生成器单测夹具里有 `socRange:{min,max}`，但 `PlanGenerator` **从不读取**。SOC 上下限 / 功率包络实际来自站点安全约束 `EmsConstraint`（`EmsPlanService.java:254-265` 生成时注入），不在 config 里。

**本次目标**：为唯一可生成类型 **PEAK_VALLEY** 提供结构化配置表单（充/放窗口表 + 功率上限），窗口即时校验（start<end、powerLimit>0），保存前 JSON 语法 + 结构化双校验阻断错误；保留「切换 JSON 模式」兜底与格式化；其余 4 类型保持 JSON 文本域（仅语法校验）。

## 2. 方案决策

### 2.1 边界（澄清确认）

| 决策点 | 结论 |
|---|---|
| socRange 处理 | **表单不含**，存量 config 中未知顶层键（含 socRange）编辑保存时**原样保留**（`{...rest, chargeWindows, dischargeWindows}` 合并回写），不静默丢弃用户数据 |
| 后端范围 | **纯前端**。不触碰后端契约；`@NotBlank` 不动 |
| 表单覆盖类型 | **仅 PEAK_VALLEY**（唯一可生成类型、schema 有权威定义）。其余 4 类型 schema 无权威来源，保持 JSON 文本域 |

### 2.2 实现方案（Approach A 采纳）

| 方案 | 做法 | 权衡 |
|---|---|---|
| **A（采纳）** | 纯逻辑模块 `strategyConfig.ts`（类型/解析/校验/序列化）+ 独立子组件 `StrategyConfigEditor.vue`（结构化表单 + JSON 模式切换 + 内联校验），`EmsStrategy.vue` 弹窗一行替换 | 逻辑与视图分家：校验纯函数可直接单测钉死 schema 契约；EmsStrategy.vue 保持轻薄；组件职责单一 |
| B | 同样的纯逻辑模块，表单内联进 `EmsStrategy.vue` | 少一个组件文件，但 EmsStrategy.vue 膨胀 400+ 行，表列逻辑与表单逻辑混杂，无法脱离组件测试 |
| C | 不拆组件不拆模块，仅加 JSON 语法校验 + 最小动态行 | diff 最小，但未兑现「按类型渲染表单」，逻辑与视图纠缠 |

## 3. 设计

### 3.1 纯逻辑模块 `frontend/src/utils/strategyConfig.ts`（新建）

```ts
/** 调度窗口：start/end 为 ISO LocalTime "HH:mm"，end 排他（对齐 PlanGenerator LocalTime.parse） */
export interface TimeWindow { start: string; end: string; powerLimit: number }
export interface PeakValleyConfig {
  chargeWindows: TimeWindow[]
  dischargeWindows: TimeWindow[]
}

/** 通用 JSON 语法校验（所有类型的 JSON 模式共用；非法返回 error，ok 时 value 为原始 unknown） */
export function parseJsonConfig(config: string): { ok: true; value: unknown } | { ok: false; error: string }

/** PEAK_VALLEY 结构化校验（value 为 parseJsonConfig 产物）：返回 config + 未知顶层键 rest（供序列化保留） */
export function parsePeakValleyConfig(value: unknown):
  | { ok: true; config: PeakValleyConfig; rest: Record<string, unknown> }
  | { ok: false; error: string }

/** 便捷入口：config 字符串 → 问题列表（空数组 = 通过）。save() 闸与组件内联提示共用 */
export function validatePeakValleyConfig(config: string): string[]

/** 序列化：{ ...rest, chargeWindows, dischargeWindows }，未知顶层键（含 socRange）原样保留 */
export function serializePeakValley(config: PeakValleyConfig, rest: Record<string, unknown>): string
```

### 3.2 校验规则（唯一事实来源，纯模块实现）

**阻断错误**（`validatePeakValleyConfig` 返回；保存被拦 + 组件内联红显）：

| # | 规则 | 报错文案（逐字） |
|---|---|---|
| 1 | JSON 语法合法 | `配置不是合法 JSON：{parse 错误信息}` |
| 2 | 顶层为对象、含 `chargeWindows` / `dischargeWindows` 数组 | `缺少 chargeWindows 或 dischargeWindows 数组` |
| 3 | 每窗口 `start` / `end` 为合法 `"HH:mm"` | `窗口 {i} 的开始/结束时间格式应为 HH:mm` |
| 4 | `start < end` | `窗口 {i} 的结束时间必须晚于开始时间` |
| 5 | `powerLimit` 为数字且 > 0 | `窗口 {i} 的功率上限必须大于 0` |
| 6 | 至少一个充电或放电窗口 | `请至少配置一个充电或放电窗口` |

（{i} 为窗口序号，从 1 起。规则 4/5/6 对齐 `PlanGenerator` 语义：start≥end 静默 0 点、powerLimit≤0 按 0 功率、空窗口仅 STANDBY 尾点——都是后端不报错但结果无意义的情形，前端提前拦截。）

**软警告**（不阻断，`el-alert warning`）：
- 结构化模式：充电窗口 `powerLimit > envelope.chargePowerMax` → `充电窗口 {i} 功率上限 {x} kW 超过站点充电功率上限 {y} kW`；放电窗口同理（`dischargePowerMax`）。
- 仅当 `envelope`（`emsApi.constraintGet(stationId)` 拉到的 `EmsConstraint`）可用时计算；包络缺失/拉取失败静默跳过。

### 3.3 子组件 `frontend/src/components/ems/StrategyConfigEditor.vue`（新建）

- **Props**：`modelValue: string`（config JSON，v-model）、`strategyType: string`、`envelope: EmsConstraint | null`。
- **Emits**：`update:modelValue`（编辑即序列化回写，`editing.config` 保持唯一数据源）。
- **PEAK_VALLEY**：
  - 模式切换 `el-radio-group`（button 样式）：`结构化编辑` / `JSON 模式`。
  - **结构化模式**：充电/放电两组窗口表。每窗口一行：`el-time-picker`（format/value-format 均 `HH:mm`）`start` + `end` + `el-input-number`（`:min="0.1"`）`powerLimit` + 删除按钮；组尾「添加充电窗口」/「添加放电窗口」。编辑 → `serializePeakValley(form, rest)` → emit；实时跑规则 3-6 内联红显。
  - **JSON 模式**：`el-input type="textarea"` + 实时 `parseJsonConfig`（绿勾「JSON 语法正确」/ 红错）+「格式化」按钮（`JSON.stringify(parsed, null, 2)`）。
  - **模式进入规则**（单一权威）：
    1. config 空/未定义（新建策略）→ 结构化模式空窗口表 + 占位提示；
    2. config 非空且可解析 → 结构化模式；
    3. config 非空但不可解析（历史脏数据/语法错）→ 强制 JSON 模式，`el-alert` 提示 `配置无法解析，已切换到 JSON 模式（结构化编辑会覆盖原配置）`，**不销毁用户数据**。
    JSON → 结构化切换时重新 parse 并填入表单；结构化 → JSON 切换时 serialize 当前表单入 textarea。
  - **外部变更同步**：`watch(() => props.modelValue)` 检测 dialog 复用时的外部换行，重新解析；自 emit 回写用守卫防循环。`strategyType` 变化时按新模式进入规则重新初始化。
- **非 PEAK_VALLEY**（DEMAND/DR/SOC_CTRL/TIME）：恒 JSON 模式，仅规则 1 语法校验。

### 3.4 `EmsStrategy.vue` 改动

- `配置 JSON` 表单项（现 `:194-196` textarea）替换为：
  ```vue
  <el-form-item label="配置 JSON">
    <StrategyConfigEditor v-model="editing.config" :strategy-type="editing.strategyType" :envelope="envelope" />
  </el-form-item>
  ```
- 新增 `const envelope = ref<EmsConstraint | null>(null)`；`openCreate`/`openEdit` 时若 `editing.stationId` 有值则 `emsApi.constraintGet` 拉包络（失败置 null 静默跳过）。
- **`save()` 校验闸**（API 调用前）：
  - PEAK_VALLEY：`const issues = validatePeakValleyConfig(editing.config ?? '')`；`issues.length` → `ElMessage.error(issues[0])` + return。
  - 其他类型：`parseJsonConfig(editing.config ?? '')` 非 ok → `ElMessage.error(error)` + return。
  - 校验通过后走既有保存逻辑（含 S2 的生成类型软警告，逻辑不变）。

### 3.5 测试

- 新建 `frontend/src/utils/__tests__/strategyConfig.spec.ts`：
  - `parseJsonConfig`：合法/非法/空串/非对象顶层。
  - `parsePeakValleyConfig`：缺数组、窗口缺字段、坏时间格式、start≥end、powerLimit 缺失/0/负数 → 各自报错文案；合法 → config 正确 + `rest` 含 socRange 等未知键。
  - `validatePeakValleyConfig`：合法返回 `[]`；上述每条规则各自触发。
  - `serializePeakValley`：`{...rest, chargeWindows, dischargeWindows}`；socRange 往返不丢；键序稳定。
- 全量 `vue-tsc` 0 错 + vitest 全绿。
- 手工冒烟：
  1. 新建 PEAK_VALLEY 策略：结构化模式添加充/放窗口 → 保存成功 → 编辑回读 JSON 键正确（chargeWindows/dischargeWindows）。
  2. 窗口 start≥end 或 powerLimit=0 → 内联红显 + 保存被拦（error 提示首条）。
  3. 切换 JSON 模式 → 文本域内容 = 序列化结果；改坏语法 → 红错，保存被拦；点「格式化」→ 排版。
  4. 存量 config 含 socRange → 结构化编辑保存后 socRange 仍在（未丢弃）。
  5. 站点包络已配置：窗口 powerLimit 超包络 → 软警告出现，但不阻断保存。
  6. DEMAND 类型策略 → 仅 JSON 文本域，语法错保存被拦。
  7. 打开一条 config 无法解析的历史策略 → 强制 JSON 模式 + 提示，原文本不被覆盖。

## 4. 影响面与风险

- **涉及文件**：新建 `frontend/src/utils/strategyConfig.ts`、`frontend/src/components/ems/StrategyConfigEditor.vue`、`frontend/src/utils/__tests__/strategyConfig.spec.ts`；修改 `frontend/src/views/EmsStrategy.vue`。后端、`models.ts`、`dicts.ts` 不动。
- **风险**：schema 契约与后端 `PlanGenerator` 解析规则漂移 → 校验规则注释指向 `PlanGenerator.java` 各行为锚点（规则 4/5/6 已对齐）；后端若新增 PEAK_VALLEY 字段（如启用 socRange），需同步本模块。
- **兼容性**：存量 config 经结构化编辑时未知键原样保留（`rest` 合并），不丢数据；无法解析的存量 config 强制 JSON 模式，不被结构化表单覆盖。
- 明确**不做**（YAGNI）：其余 4 类型结构化表单（schema 无权威）、后端保存时 JSON 校验、`socRange` 表单化、组件级单测（纯模块已覆盖逻辑，组件走 vue-tsc + 手工冒烟）。

## 5. 验收标准

1. PEAK_VALLEY 策略在弹窗中以结构化表单编辑充/放窗口；`start<end`、`powerLimit>0`、至少一个窗口、合法 `HH:mm` 校验生效，违规保存被阻断并提示首条错误。
2. 支持切换 JSON 模式：文本域内容与结构化表单双向一致；JSON 语法错保存被拦；「格式化」可用。
3. 存量 config 的未知顶层键（含 socRange）编辑保存后原样保留。
4. 站点包络已配置时，超包络窗口出现软警告但不阻断保存；包络缺失时静默跳过。
5. 非 PEAK_VALLEY 类型仍为 JSON 文本域，仅语法校验。
6. `vue-tsc` 0 错；vitest 全绿（含 `strategyConfig.spec.ts`）。
