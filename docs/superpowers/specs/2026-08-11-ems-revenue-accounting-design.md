# P1-1 收益核算/经济评估 设计

> 迭代：EMS P1-1（评估路线图 `docs/review/2026-08-11-EMS功能完整性与路线图.md` P1-1 收益核算）
> 日期：2026-08-11 ｜ 状态：已批准（brainstorming 澄清 4 决策后确认）
> 关联：`docs/review/2026-08-11-EMS功能完整性与路线图.md`（G4 收益核算缺失）、P0-1（电价快照 `plan_param.priceSnapshot`）、P0-2（PCS 按电站解析 `pcsDeviceMapper.selectByStation`）、P0-7（跨服务 Feign 契约）

---

## 1. 背景与问题

energy-ems 的**经济性功能完全缺失**（G4）：市面工商业储能 EMS 的"盈利中枢"——收益核算/经济评估（日/月/年收益、成本、ROI/回本周期）在本项目前后端均不存在。数据（执行记录、电价、功率曲线）已全部就位，但无人聚合：`ems_electricity_price` 有档位与有效期、`ems_plan_point`（TDengine）有计划点序列、`ems_execution_record` 有逐点执行状态、PCS 设备有实际遥测（TSDB `power`/`runMode`）。

**本迭代**：新增收益核算后端接口 + 前端收益页，按**实际遥测口径**聚合电站充放电量与峰谷套利收益，并给出 ROI/回本周期。

## 2. 目标与非目标

### 2.1 目标

- 新增收益核算接口：时段 summary（充放电量/套利收益/需量节省占位/ROI）、趋势曲线、单日逐槽明细、电站投资元数据读写
- **电量口径 = 实际遥测**：TSDB `power` 幅值按采样间隔积分（非计划点功率）
- **充放电方向 = runMode 优先**（1充/2放），runMode 缺失时**回退当日计划动作**，两者皆无则槽位不参与
- **电价**：当日计划为电价驱动且有 `plan_param.priceSnapshot` → 用快照档；否则 → 电价表 `status=1` 且有效期覆盖该日
- ROI：`paybackYears = investment ÷ 年化收益`（YEAR 当年 / MONTH ×12 / DAY ×365）；年化按投运日期截断（投运前天数不计入回本核算）
- 前端收益页：KPI 卡片 + 趋势/明细图表 + 投资额设置

### 2.2 非目标（SAFE-TO-DEFER）

- **需量电费节省**：卡片置 0/N/A，真实需量检测与节省估算属 P1-2 需量管理（本期不做）
- 日预聚合汇总表/定时任务：P1-1 选**实时聚合**（方案 A），电站规模上来后再演进
- 实际功率与计划功率的偏差分析、充放电转换效率（P2-2 损耗/效率分析）
- 修改 energy-tsdb 服务侧代码（无聚合端点，按日拉原始遥测）
- 遥测缓存（每请求拉取，demo 规模可接受）

## 3. 设计

### 3.1 后端接口契约

网关 `/api/ems/**` → energy-ems（StripPrefix=0，控制器映射带 `/ems`）。新控制器 `@RequestMapping("/ems/revenue")`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ems/revenue/summary?stationId=&periodType=DAY\|MONTH\|YEAR&date=` | 时段收益卡片 |
| GET | `/ems/revenue/trend?stationId=&periodType=MONTH\|YEAR&date=` | 趋势曲线（月视图按日、年视图按月） |
| GET | `/ems/revenue/detail?stationId=&date=` | 单日逐槽明细（含方向来源标记，供对账） |
| GET | `/ems/revenue/meta?stationId=` | 查电站投资额/投运日期 |
| PUT | `/ems/revenue/meta` | upsert 电站投资元数据 |

**Summary 响应字段**：`stationId / periodType / startDate / endDate / daysCount`、`chargeEnergy / dischargeEnergy / totalEnergy (kWh)`、`arbitrageRevenue / demandSavings(=0) / totalRevenue (元)`、`investmentAmount / paybackYears / hasInvestment`。

**Trend 响应字段**（List）：`label / chargeEnergy / dischargeEnergy / revenue`。月视图 label=日（`08-01`）、年视图 label=月（`2026-08`）。

**Detail 响应字段**（List）：`time / action(CHARGE|DISCHARGE) / energyKwh / price / revenue / source(RUN_MODE|PLAN)`。

**Meta**：`stationId / investmentAmount / installDate`。

### 3.2 聚合语义（`RevenueCalculator`，纯函数可单测）

对每台 PCS 设备 × 期间每一天：

1. **拉遥测**：Feign 调 energy-tsdb `GET /tsdb/property/history`，`identifiers="power,runMode"` 一次取两个属性，`order=asc`，`size=1000`（5 分钟粒度全天 288 行，单页覆盖）。多台 PCS 并行拉取。
2. **逐槽积分**：相邻采样点 `dt = Δts / 3_600_000 h`；`energy = |power| × dt`（用左采样点功率）。`dt` 上限钳制为 `MAX_SLOT_HOURS`（默认 1h）：缺报长间隔不按满功率计，防数据空洞虚增电量。
3. **定方向**：`runMode==1 → 充电`、`runMode==2 → 放电`；该时刻无 runMode → 回退当日计划该时刻动作（`TdenginePlanWriter.read(stationId, date)` 取计划点，按时刻匹配最近 5min 槽）；两者皆无 → 该槽不参与。
4. **定电价**：当日计划为电价驱动且 `plan_param.priceSnapshot` 存在 → 用快照档（按时刻匹配 `[start, end)`）；否则 → 电价表 `status=1` 且 `validFrom ≤ date ≤ validTo` 的档（同样按时刻匹配）。无电价槽位计电量、收益记 0。
5. **累计**：`收益 = Σ(放电电量×电价) − Σ(充电电量×电价)`；按日/月归并成 trend 序列。

### 3.3 后端新增/修改文件

**新增（`backend/energy-ems`）**：

- `web/EmsRevenueController`：5 个端点，薄控制器
- `web/dto/*`：`RevenueSummary / RevenueTrendPoint / RevenueDetailRow / RevenueMetaReq`（含校验）
- `service/EmsRevenueService`：编排——`PcsDeviceMapper.selectByStation` 解析 PCS → 逐日取计划（`planMapper` by station+date range）与电价（`priceMapper` by station+status=1+有效期）→ 组装价格查找表 → 调 `RevenueCalculator` → 汇总 summary/trend/detail
- `service/RevenueCalculator`：纯聚合逻辑（见 3.2），入参含遥测获取函数（可注入 mock）
- `client/TsdbFeignClient`：`@FeignClient(name="energy-tsdb", path="/tsdb")`，`@GetMapping("/property/history") Result<TsdbHistoryViewDto> history(...)`（参数同 TsdbController），遵循跨服务 Feign 契约（不写死 URL、接口不用 default 方法）
- `client/TsdbHistoryViewDto` / `client/TsdbHistoryRecordDto`：本地投影（`deviceId/productKey/total/records`；`ts: long`、`values: Map<String,Object>`），不依赖 energy-tsdb 模块
- `entity/EmsStationMeta` + `mapper/EmsStationMetaMapper`
- `resources/db/migration/V5__revenue_meta.sql`：`ems_station_meta(station_meta_id PK AUTO, tenant_id, station_id UK, investment_amount DECIMAL(12,2), install_date DATE, create_time, update_time)`

**修改**：

- `application.yml`：无新增 URL 配置（Feign 按 Nacos 服务名解析）

### 3.4 前端

新页面 `/ems/revenue`（收益核算，icon `Money`，菜单插在「充放电计划」后）。

- **KPI 卡片**：充电量 / 放电量 / 总电量 (kWh)、套利收益 / 需量节省(0/N/A) / 累计收益 (元)、ROI 卡片（投资额 + 回本周期；未设置投资额 → 引导按钮）
- **主图表（ECharts）**：日 → 逐槽充/放能量柱 + 收益折线 + 电价底纹（复用 EmsPlan 模式）；月 → 每日能量柱 + 收益折线；年 → 每月能量柱 + 收益折线
- **单日对账表**（日视图）：时刻 / 方向 / 能量 / 电价 / 收益 / 方向来源(runMode/计划)
- **设置投资额弹窗**：投资额 + 投运日期 → `PUT /ems/revenue/meta` → 重算 ROI
- **交互**：电站联动（支持 `?station=` 直达）、维度切换重拉、空态提示（无 PCS/无遥测/无电价 → 卡片 `—` + 原因，不报错）

**前端文件**：

- `views/EmsRevenue.vue`（新增）
- `api/ems.ts`：追加 `revenueSummary / revenueTrend / revenueDetail / revenueMetaGet / revenueMetaPut`
- `router/index.ts`：注册 `/ems/revenue`
- `types/models.ts`：追加 `RevenueSummary / RevenueTrendPoint / RevenueDetailRow / EmsStationMeta`
- 复用 `stationDict.loadStations()`、`priceTypeTag/Text`、EmsPlan 电价底纹/图表模式

## 4. 验证

### 4.1 后端单测（纯单测，不起 Spring 上下文，沿用仓库风格）

- **`RevenueCalculatorTest`**：
  - runMode 定方向优先、缺失回退计划动作
  - dt 积分正确（能量 = |power|×dt）与 dt 上限钳制（空洞不虚增）
  - 电价匹配：priceSnapshot 优先于电价表；无电价槽收益记 0
  - 收益符号：放电加、充电减；同量充放电收益为 0
  - 多设备/多日归并 → 月/年 trend 序列正确
- **`EmsRevenueServiceTest`**：mock TsdbFeignClient + 计划点 + 电价 → summary/trend/detail 组装、空态（无 PCS/无遥测）、ROI 年化与 paybackYears
- **`EmsStationMetaServiceTest`**：upsert 幂等（station_id 唯一，重复保存不叠行）

### 4.2 冒烟验证（运行时，可选）

本地起 Nacos + energy-tsdb + energy-ems，向 PCS 上报含 `runMode` 的功率遥测 → `GET /ems/revenue/summary`，核对收益 = Σ(放电|P|×dt×峰价) − Σ(充电|P|×dt×谷价)；停掉 runMode 上报 → 方向回退计划动作且收益仍可算。Feign 按 Nacos 服务名 `energy-tsdb` 解析。

## 5. 跨模块契约

- **energy-tsdb**：只读现有 `GET /tsdb/property/history`（参数：deviceId/productKey/identifiers/startTime/endTime/order/page/size；`size` 上限 1000；返回 `Result<PropertyHistoryView>`）。不改 tsdb 侧代码。
- **PCS 物模型**（energy-product 种子）：`power`（float kW，无符号约定）+ `runMode`（enum 0待机/1充电/2放电）。方向约定由此驱动。
- **电价快照**（P0-1）：`plan_param.priceSnapshot` = `[{priceType, start, end, price}]`，仅电价驱动计划生成时写入。
