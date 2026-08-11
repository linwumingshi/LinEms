# 收益核算（P1-1）使用手册

> 版本：2026-08-11 · 配套代码：energy-ems P1-1 收益核算（commit `de98b92`）与 P1-2 需量节省接入
> 设计文档：`docs/superpowers/specs/2026-08-11-ems-revenue-accounting-design.md`

## 一、这是什么

收益核算是储能电站的「盈利中枢」：按**实际遥测口径**聚合电站的充放电量与峰谷套利收益，并给出 ROI / 回本周期。

本功能提供四个能力：

1. **时段收益卡片** —— 日/月/年的充电量、放电量、套利收益、累计收益
2. **收益趋势** —— 月视图按日、年视图按月，看收益曲线
3. **单日对账明细** —— 逐槽列出充/放电能量、电价、收益与方向来源，供对账
4. **投资回报** —— 设置投资额与投运日期，算回本周期

## 二、前置条件（数据来源）

| 条件 | 说明 |
|---|---|
| **PCS 设备** | 在线 PCS（productKey=`snd_ess_pcs`），其功率遥测（TSDB `power`，kW）与 `runMode`（0待机/1充电/2放电）是电量与方向的来源 |
| **runMode 上报** | 决定充/放方向。缺失时回退当日计划动作，两者皆无的槽位**不参与**核算 |
| **电价** | 当日计划为电价驱动且带 `plan_param.priceSnapshot` → 用快照档；否则用电价表 `status=1` 且有效期覆盖当日的档位。无电价的槽位**计电量、收益记 0** |
| **储能系统** | 收益页依赖 energy-tsdb（遥测历史，Feign 按 Nacos 服务名 `energy-tsdb` 解析）与已生成的计划（供方向回退与电价快照） |

## 三、怎么用（前端页面「收益核算」）

入口：左侧菜单 **EMS → 收益核算**（路由 `/ems/revenue`）。

### 1. 筛选查看

- **电站** —— 下拉选择（支持 `?station=` 直达）；清空则空态
- **维度** —— 日 / 月 / 年
- **日期** —— 随维度切换粒度（日 / 月 / 年选择器）

### 2. 读面板

- **KPI 卡片**：
  - 充电量 / 放电量 / 总电量（kWh）
  - 套利收益（元）/ **需量节省（页面当前显示 `—`，P1-1 占位）** / 累计收益（元）
  - **回本周期**：已设投资额 → 显示 `paybackYears 年`；未设置 → 显示「未设置投资额」并提示设置
- **主图表**（ECharts）：充电量（蓝）+ 放电量（橙）堆叠柱 + 收益（绿）折线（右轴 元）
  - 日 → 逐槽（同刻多台 PCS/多行按 `time` 合并为一柱）
  - 月 → 每日；年 → 每月
- **单日对账表**（仅日视图）：时刻 / 方向（CHARGE|DISCHARGE）/ 能量 (kWh) / 电价 (元/kWh) / 收益 (元) / **方向来源**（RUN_MODE=runMode 定向、PLAN=回退计划动作）——供核对口径
- **空态**：无 PCS / 无遥测 / 无电价 → 卡片显示 `—`（不报错），明细表空态提示

### 3. 设置投资额（ROI）

点右上角「**设置投资额**」弹窗：

- **投资额 (元)** —— 必填，> 0。总装机投资
- **投运日期** —— 选填。用于年化收益截断（投运前的天数不计入回本核算）

点「保存」即 upsert，保存后重算 ROI。

## 四、核算口径（怎么算出来的）

1. **逐槽积分电量**：对每台 PCS × 期间每一天，按 5 分钟遥测采样，`能量 = |功率| × Δt`（用左采样点功率）；相邻采样间隔上限钳制 1 小时，防缺报长间隔按满功率虚增电量
2. **定方向**：`runMode==1` 充电、`runMode==2` 放电；该时刻无 runMode → 回退当日计划该时刻动作；两者皆无 → 该槽不参与
3. **定电价**：计划带电价快照 → 用快照档（按时刻匹配）；否则电价表 `status=1` 且有效期覆盖该日
4. **算收益**：`收益 = Σ(放电电量×电价) − Σ(充电电量×电价)`；同量充放电收益为 0；无电价槽位计电量收益记 0

**回本周期**：`paybackYears = 投资额 ÷ 年化收益`（年视图当年值 / 月视图×12 / 日视图×365 折算年化）。

## 五、对外接口（网关 `/api/ems/revenue/**`）

| 接口 | 用途 | 说明 |
|---|---|---|
| `GET /summary?stationId&periodType&date` | 时段收益卡片 | 字段：chargeEnergy / dischargeEnergy / totalEnergy (kWh)、arbitrageRevenue / demandSavings / totalRevenue (元)、investmentAmount / paybackYears / hasInvestment、daysCount |
| `GET /trend?stationId&periodType=MONTH\|YEAR&date` | 趋势曲线 | 月视图按日（label=`08-01`）、年视图按月（label=`2026-08`）；每点 label / chargeEnergy / dischargeEnergy / revenue |
| `GET /detail?stationId&date` | 单日逐槽明细 | 每行 time / action / energyKwh / price / revenue / source(RUN_MODE\|PLAN) |
| `GET /meta?stationId` | 查投资元数据 | 返回 `{stationId, investmentAmount, installDate}` |
| `PUT /meta` | 保存投资元数据 | body `{stationId, investmentAmount, installDate}`，upsert（重复保存不叠行） |

## 六、数据模型速记

- **`ems_station_meta`** —— 每租户每站一条（`station_id` 唯一），存 `investment_amount`（投资额）、`install_date`（投运日期）
- **聚合输入**：TSDB `power`/`runMode` 遥测（energy-tsdb）＋ `ems_plan_point` 计划点（方向回退）＋ `plan_param.priceSnapshot` 电价快照 / `ems_electricity_price` 电价表

## 七、验收 / 联调步骤

1. 本地起 Nacos + energy-tsdb + energy-ems，向 PCS 上报含 `runMode` 的功率遥测
2. `GET /api/ems/revenue/summary?stationId&periodType=DAY&date`，核对收益 = `Σ(放电|P|×Δt×峰价) − Σ(充电|P|×Δt×谷价)`
3. 停掉 `runMode` 上报 → 方向回退计划动作，收益仍可算（明细表 source 显示 `PLAN`）
4. 设置投资额 → 回本周期卡片给出数值；日/月/年切换 → 图表与卡片随之变化

## 八、已知边界（使用注意）

- **需量节省卡片**：前端收益页当前固定显示 `—`（P1-1 占位）。后端 `summary.demandSavings` 自 P1-2 起已返回真实需量节省估算，收益页展示接入待后续；实时需量节省请到「需量管理」页查看
- **口径是实际遥测**：收益按 TSDB 功率积分，非计划功率——无遥测或方向未知的时段不参与核算（卡片可能偏低属预期）
- **收益符号**：放电为正、充电为负；仅峰谷价差才有套利收益
- **年化截断**：设置投运日期后，投运前的天数不计入年化收益，回本周期按投运后实际收益折算
