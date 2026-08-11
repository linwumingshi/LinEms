# P0-1 电价驱动计划生成 设计

> 迭代：EMS P0-1（评估路线图 `docs/review/2026-08-11-EMS功能完整性与路线图.md` P0-1）
> 日期：2026-08-11 ｜ 状态：已批准（brainstorming 澄清 3 决策后确认）
> 关联：`docs/superpowers/specs/2026-08-08-ems-ux-evaluation.md`（S1 结构化表单前置）、`docs/review/2026-08-11-EMS功能完整性与路线图.md`（G1 假闭环）

---

## 1. 背景与问题

`PlanGenerator.generate()` 是纯函数，目前**只读** config 的 `chargeWindows`/`dischargeWindows` 手工窗口，`PlanInput.prices()`（已由 `EmsPlanService.toInput()` 传入 `PriceTier` 列表）从头到尾不读。分时电价模块（`ems_electricity_price`）因此是"死业务"：CRUD 完整、前端电价底纹完整，但对计划生成**零影响**——充放窗口完全来自手工配置，且生成期查电价不过滤 `status=1`/`valid_from~valid_to`（停用/过期电价也参与）。

**本迭代**：让电价真正驱动 PEAK_VALLEY 计划生成（`priceDriven:true` 时按档位自动推导谷充峰放窗口），并修复电价查询的有效性过滤。

## 2. 目标与非目标

### 2.1 目标

- config 新增 `priceDriven:true` 开关 → 生成时按电价档位自动推导充放窗口：**DEEP/VALLEY→CHARGE，PEAK/PEEK→DISCHARGE，FLAT/未覆盖→STANDBY**
- 功率 = config `chargePower`/`dischargePower`（有则用，无则回退安全包络对应上限）
- 生成期只取 `status=1` 且在 `valid_from ≤ planDate ≤ valid_to` 内的电价
- 无生效电价时生成报明确错误（消除"静默 0 点"假可用）
- 手工窗口模式**完全保留**（`priceDriven` 缺省 false，已存策略不受影响）

### 2.2 非目标（SAFE-TO-DEFER，后续迭代）

- 电价 `batchSave` 幂等 upsert、策略 config 后端 JSON schema 校验（P0-5 数据治理）
- PCS 按电站配置下发（P0-2）
- DEMAND/DR/SOC_CTRL/TIME 策略类型落地（P0-4）
- 收益核算/经济评估（P1-1）
- 电价驱动模式的"两充两放价差寻优"（YAGNI：档位映射天然支持多段，够用）

## 3. 设计

### 3.1 后端 `PlanGenerator.java`（纯函数，核心改动）

```
generate(in):
  cfg = parse(in.config())
  priceDriven = cfg.priceDriven (缺省 false)

  ── priceDriven = false（默认）→ 现有手工窗口逻辑【完全不变】
     遍历 cfg.chargeWindows / dischargeWindows，逐 5 分钟出点 + SOC 演进 + 尾点

  ── priceDriven = true → 电价驱动分支：
     tiers = in.prices() 已按 startTime 升序
     按 startTime 去重（保留首条）防重叠档位产生同刻双点（batchSave 非幂等的防御）
     tiers 为空 → throw IllegalArgumentException("未配置生效的分时电价")
     for tier in tiers:
       action = switch(tier.priceType()):
         DEEP|VALLEY → CHARGE    PEAK|PEEK → DISCHARGE    FLAT|其他 → 跳过（待机）
       power = switch(action):
         CHARGE     → cfg.chargePower (>0) ?: in.chargePowerMax()
         DISCHARGE  → cfg.dischargePower (>0) ?: in.dischargePowerMax()
       for t in [tier.start(), tier.end()) 步长 5min:
         复用现有 SOC 演进（CHARGE 到 socMax 停、DISCHARGE 到 socMin 停）
         add(PlanPoint(t, action, power, soc))
     未覆盖时段（档位 gap）→ 天然待机
     尾点 STANDBY 23:55 锚定时间轴（与现有逻辑一致）
     按时间升序 sort
```

要点：
- 功率字段 `cfg.path("chargePower").asDouble(0)` / `dischargePower`，`<=0` 视为缺失 → 回退 `in.chargePowerMax()`/`in.dischargePowerMax()`
- SOC 演进公式沿用现有近似：`soc += power * SLOT_MIN / 60.0 * 0.01`（充）/ 反向（放）
- 出点动作统一 `CHARGE`/`DISCHARGE`，与现有下发链路（`CommandClient.dispatch`）契约一致

### 3.2 后端 `EmsPlanService.generate()`

**电价查询过滤**（`EmsPlanService.java:113-115` 现仅 tenant+station）：

```java
List<EmsElectricityPrice> prices = priceMapper.selectList(
    new LambdaQueryWrapper<EmsElectricityPrice>()
        .eq(EmsElectricityPrice::getTenantId, tenant)
        .eq(EmsElectricityPrice::getStationId, stationId)
        .eq(EmsElectricityPrice::getStatus, 1)
        .le(EmsElectricityPrice::getValidFrom, planDate)
        .ge(EmsElectricityPrice::getValidTo, planDate)
        .orderByAsc(EmsElectricityPrice::getStartTime));
```

**无生效电价拦截**：`toInput` 前解析 `strategy.getConfig()` 的 `priceDriven`；若为 true 且 `prices` 为空 → `BusinessException(ErrorCode.NOT_FOUND, "该电站 {planDate} 未配置生效的分时电价（status=1 且在有效期内）")`。

**plan_param 快照**：
- `priceDriven=true` → `plan.setPlanParam(<{...config, priceSnapshot:[{priceType,start,end,price}]}>)`（JSON 序列化）
- `priceDriven=false`（默认）→ `plan.setPlanParam(strategy.getConfig())` **原样，兼容已有数据**

`toInput()` 无需改动（已传 prices + 包络参数）。

### 3.3 保存链路（后端无需改动）

`config` 是 JSON 字符串（`EmsStrategySaveReq`），`priceDriven`/`chargePower`/`dischargePower` 由前端序列化后原样入库，后端 `@NotBlank` 校验不变。

### 3.4 前端 `strategyConfig.ts`

- `validatePeakValleySaveable`：parse 后若 `obj.priceDriven === true` → 直接 `return []`（电价驱动：窗口可空、功率缺省回退包络，不强制"至少一个窗口"）
- 其余函数（`parsePeakValleyConfig`/`serializePeakValley`/`validatePeakValleyConfig`）**不改 rest 语义**：三个新键本就经 rest 原样保留（`serializePeakValley` = `{...rest, chargeWindows, dischargeWindows}`）

### 3.5 前端 `StrategyConfigEditor.vue`

- 新增 state：`priceDriven = ref(false)`、`chargePower = ref<number|undefined>(undefined)`、`dischargePower = ref<number|undefined>(undefined)`
- `initFromConfig`：结构化解析后从 `rest` 读取三键回填（`rest.value.priceDriven` 等）
- 模板（form 模式）：
  - 顶部 `el-switch`「电价驱动」绑定 `priceDriven`
  - **开启**：渲染 `chargePower`/`dischargePower` 两个 `el-input-number`（可空，placeholder「留空回退包络上限」），**隐藏**充/放窗口表
  - **关闭**：现有窗口表（不变）
- watch：三键变化时同步进 `rest.value` 并 `emitConfig()`（复用现有 `serializePeakValley(form, rest)` rest 合并机制）
- 模式进入/切换逻辑（`initFromConfig`/`switchMode`）适配：`priceDriven=true` 时也走 form 模式（编辑功率），不强制 JSON

### 3.6 前端 `EmsStrategy.vue` / `planGate.ts`

**无改动**。`STRATEGY_GENERATABLE_TYPES=['PEAK_VALLEY']` 不变；无生效电价的明确错误由后端 `BusinessException` 文案经 `toFriendlyError` 透传到 toast。

## 4. 数据流

```
策略(priceDriven=true) → POST /api/ems/plan/generate {stationId, planDate}
  → EmsPlanService.generate()
      → 查生效电价(status=1 + valid_from≤date≤valid_to, startTime 升序)
      → 无生效电价 → BusinessException("未配置生效的分时电价")
      → PlanInput(prices=..., socInit=包络中点, 包络限值...)
      → PlanGenerator: priceDriven 分支 → 档位→动作 → 逐5min出点(功率=config?:包络) → SOC 演进
      → SafetyEnvelopeValidator（不变）
      → TdenginePlanWriter 写点 + ems_plan 计划头(plan_param 含电价快照)
  → 前端波形：点序列 + 电价底纹（既有渲染，无改动）
```

## 5. 错误处理

| 场景 | 行为 |
|---|---|
| priceDriven=true 无生效电价 | `BusinessException("该电站 {planDate} 未配置生效的分时电价（status=1 且在有效期内）")`——**Service 层先拦截**（查空提前抛）；`PlanGenerator` 内保留 `IllegalArgumentException` 作纯函数防御（直接调用/单测 5 触发），两者不冲突 |
| 电价档未覆盖 24h | 未覆盖时段待机，不报错 |
| chargePower/dischargePower 缺失或 ≤0 | 回退包络上限，不报错 |
| 电价档位重叠（batchSave 非幂等残留） | 按 startTime 去重保留首条（防御），不报错 |
| priceDriven=false（手工模式） | 行为完全不变 |

## 6. 测试与验收

### 6.1 后端单测

`PlanGeneratorTest`（纯函数，新增）：
1. 标准谷充峰放：DEEP/VALLEY 段出 CHARGE、PEAK/PEEK 段出 DISCHARGE、FLAT 段无点
2. 多段（两充两放）：多个谷/峰档位各自出段
3. 功率优先级：config.chargePower=80 且包络 100 → 点功率 80；config 缺失 → 点功率=包络上限
4. SOC 边界裁剪：长谷段充到 socMax 后剩余时刻待机；长峰段放到 socMin 后停
5. 无电价 → `IllegalArgumentException`
6. 档位重叠去重：同 startTime 双档只出一段
7. 手工模式回归：priceDriven 缺省/false 时现有窗口逻辑输出不变

`EmsPlanServiceTest`（Mockito）：
8. 电价查询含 status=1 + valid_from/to 过滤
9. priceDriven=true 且查无生效电价 → `BusinessException`
10. plan_param：priceDriven=true 含 `priceSnapshot`；false 原样 config

### 6.2 前端 vitest

`strategyConfig.spec.ts` 新增：
11. `validatePeakValleySaveable`：priceDriven=true 且无窗口 → 通过；false 且无窗口 → 仍拦截
12. `serializePeakValley`：rest 含三新键时序列化原样保留

### 6.3 冒烟清单

1. 建策略 `priceDriven=true`（chargePower/dischargePower 缺省）+ 配电价（DEEP 00-08 / PEAK 08-11 / FLAT 11-14 / VALLEY 14-18 / PEEK 18-22）→ 生成计划 → 波形：谷段充电、峰段放电、平段待机
2. 不配电价 → 生成 → toast「未配置生效的分时电价」
3. 电价 `status=0` 或 `valid_to < planDate` → 生成 → 同 2 报错
4. config 带 chargePower=80 → 计划点功率 80；删字段 → 点功率=包络上限
5. 手工窗口策略（旧数据，无 priceDriven）→ 生成 → 波形与改动前一致

### 6.4 gate

`mvn -pl energy-ems -am test` 全绿 + `vue-tsc --noEmit` 0 错 + `vitest run` 全绿。

## 7. 相关文件

| 文件 | 改动 |
|---|---|
| `backend/energy-ems/src/main/java/com/energyx/ems/util/PlanGenerator.java` | 新增 priceDriven 分支 |
| `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsPlanService.java` | 电价查询过滤 + 无生效电价拦截 + plan_param 快照 |
| `backend/energy-ems/src/test/java/com/energyx/ems/util/PlanGeneratorTest.java` | **已存在**，追加 priceDriven 用例 |
| `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsPlanServiceTest.java` | **已存在**，追加用例 |
| `frontend/src/utils/strategyConfig.ts` | validatePeakValleySaveable 豁免 |
| `frontend/src/components/StrategyConfigEditor.vue` | 电价驱动开关 + 功率输入 |
| `frontend/src/utils/__tests__/strategyConfig.spec.ts` | **已存在**，追加用例 |
| `frontend/src/components/__tests__/StrategyConfigEditor.spec.ts` | **已存在**，追加用例（组件交互：开关切换） |
