# P1-2 需量管理 设计文档

> 路线图：P1-2「需量管理」— 15min 滑动窗口最大需量检测 + 超限削峰策略 + 基本电费节省估算（对接 DEMAND 策略）。验收：需量超限告警 + 削峰指令 + 节省估算。

## 背景与目标

**问题**：工商业电费中基本电费按「最大需量」（15min 平均功率的最大值）计费，超限部分收费高。储能电池可在需量超限的 15min 窗口内放电削峰，把进线需量压回限值以下，从而节省基本电费。本项目目前：
- 无进线功率数据源（设备目录只有 `ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW`，无电表类型）；
- DEMAND 策略的 `demandLimit` 字段被校验但**从未消费**（`PlanGenerator` 无 DEMAND 专属分支）；
- 收益核算的 `demandSavings` 恒为 0（`EmsRevenueService` 硬编码）。

**目标**：新增电表设备类型承载进线功率遥测；按 15min 固定槽位检测站点需量；超限时实时向站内 PCS 下发削峰放电指令并留痕；发布需量超限事件接入平台告警链路；估算基本电费节省并接入 `demandSavings`；提供需量管理前端页。

## 已定决策（用户确认）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 功率数据源 | **新增 METER 设备类型**（电表物模型 `importPower`），检测读电表功率 |
| 2 | 需量配置来源 | **独立站点需量配置表** `ems_demand_config`（限值 + 费率），与 DEMAND 策略解耦 |
| 3 | 削峰执行方式 | **实时检测 + 实时削峰指令**（每 min 轮询，超限即向 PCS 下 DISCHARGE） |
| 4 | 超限告警呈现 | **发布 iot-thing-event(demandOverLimit) 标准事件** + 需量管理页内超限记录 |

## 架构方案（用户选 A）

**TSDB 轮询检测 + 事务性削峰**：
- 1min `@Scheduled` + 分布式锁（复用 `PlanExecutionScheduler` 模式）轮询；
- 复用 `TsdbClient`（功率遥测）、`CommandClient`（削峰下发）、`EmsKafkaProducer`（事件发布）、`MeterDeviceMapper`（站→电表解析）；
- 无新 Kafka 消费者、无内存态、多实例安全。

否决方案 B（Kafka 流式检测：秒级延迟对 15min 需量无意义，违背「Kafka 线程忽略 TenantContext」约束，多实例状态分片）、方案 C（削峰硬塞进 `ems_execution_record` 计划语义：耦合且需量页仍需独立记录）。

## 核心语义：固定 15min 槽位

需量检测按**固定 15min 槽位**（00:00–00:15, 00:15–00:30, …，共 96 槽/天）而非严格滑动窗口：
- 与工商业需量计费口径一致；
- 每站每槽位一条 `ems_demand_record`，天然形成需量曲线，页面查询稳定；
- 每分钟轮询当前槽位，槽位结束时记录定型。

## 数据模型

### 表 ① `ems_demand_config`（energy-ems V6 迁移，仿 `ems_station_meta`）

| 列 | 类型 | 含义 |
|---|---|---|
| `demand_config_id` | BIGINT PK AI | |
| `tenant_id` | BIGINT NOT NULL | |
| `station_id` | BIGINT NOT NULL | UNIQUE(tenant_id, station_id) |
| `demand_limit_kw` | DECIMAL(10,2) | 需量限值 kW，超限即削峰/告警 |
| `demand_rate` | DECIMAL(8,4) | 需量费率 ¥/kW·月（基本电费单价） |
| `create_time` / `update_time` | DATETIME(3) | |

### 表 ② `ems_demand_record`（每站每槽位一条，upsert 幂等）

| 列 | 类型 | 含义 |
|---|---|---|
| `demand_record_id` | BIGINT PK AI | |
| `tenant_id` / `station_id` | BIGINT | |
| `window_start` / `window_end` | DATETIME(3) | 槽位起止，UNIQUE(station_id, window_start) |
| `demand_kw` | DECIMAL(10,2) | 该槽位实际需量（15min 平均功率） |
| `limit_kw` | DECIMAL(10,2) | 限值快照 |
| `over_limit` | TINYINT(1) | 是否超限 |
| `shaved_kw` | DECIMAL(10,2) | 削峰放电功率（未削峰=0） |
| `action` | VARCHAR(16) | `NONE` / `SHED` / `SHED_FAILED` / `ALARM_ONLY` |
| `create_time` | DATETIME(3) | |

### 电表产品（METER 设备类型）

- `energy-product` 种子新增 product `snd_ess_meter`（`device_type='METER'`，物模型属性 `importPower` float kW 进线功率 accessMode=r，仿 PCS 种子 `snd_ess_pcs`）。
- EMS 侧新增 `MeterDeviceMapper`（仿 `PcsDeviceMapper`）：`device_type='METER' AND product_key=#{productKey} AND deleted=0 AND status IN (2,3)`，跨库读 `es_device.iot_device`。
- product key 配置 `energyx.ems.meter-product-key:snd_ess_meter`（仿 `energyx.ems.product-key:snd_ess_pcs`）。
- 前端设备管理若 device_type 有硬编码枚举需加 `METER`（如为自由文本则不改）。

## 检测/削峰循环 + 数据流

### `DemandDetectScheduler`（每 min）

- `@Scheduled(cron = "0 * * * * *")` + 分布式锁 `scheduled:ems-demand-detect`（复用 `PlanExecutionScheduler` 模式）。
- 每 min：遍历有需量配置的站 → `MeterDeviceMapper` 解析站内电表 → `TsdbClient.history(电表, 当日)` 整日拉取、内存按当前槽位 filter → 槽位均值 = mean(power)。

### 超限判定 → 削峰 → 留痕 → 告警（`DemandDetector` 纯函数）

```
槽位均值 > limit 且站内有活跃 PCS？
 ├─ 是 → 削峰功率 = min(均值 − limit, ΣPCS 可用功率)
 │         （ΣPCS 可用功率 = 活跃 PCS 数 × 单台额定功率，简化；SOC 深放保护由 socTarget 兜底）
 │    → CommandClient.dispatch 向站内各 PCS 均分下 DISCHARGE（params: {action:"DISCHARGE", power, socTarget, time}）
 │    → upsert 该槽位记录（over_limit=true, action=SHED, shaved_kw=削峰功率）
 │    → 发布 iot-thing-event(demandOverLimit)
 ├─ 无活跃 PCS → 只告警 + 记录 action=ALARM_ONLY（不削峰）
 ├─ dispatch 异常 → catch，记录 action=SHED_FAILED，不中断循环
 └─ 否 → upsert 记录（over_limit=false, shaved_kw=0），不告警
```

执行细节：
- **检测值**：槽位中段以「当前已积累样本均值」作为检测值（早期预警，槽位中途即可越限触发）；外推/全窗口判定留后续优化。记录 `demand_kw` 最终为槽位全 15min 均值（upsert 定型）。
- **socTarget**：削峰放电的放停下限，取站内 PCS 影子实时 soc（P0-7 影子 SOC 链路），无则回退 30（防深放）。
- **多 PCS**：削峰功率按站内活跃 PCS 均分（复用 P0-2 多 PCS 下发）。
- **槽位数据源**：`TsdbClient.history` 返回整日 rows，内存按当前槽位 filter（量小，不改 TSDB 查询）。
- **失败韧性**：电表不可用 / 无遥测 / TSDB 失败 → 跳过该站该轮（log.warn），绝不 fail 整个循环。

```
[METER 设备上报 importPower → MQTT uplink → TSDB]
   ↓
[DemandDetectScheduler 每min]  ← 读 ems_demand_config（哪些站参与）
   ↓ TsdbClient.history(电表, 当日)
[DemandDetector 纯函数] 槽位均值 vs 限值
   ├→ CommandClient.dispatch(DISCHARGE) → PCS   （削峰）
   ├→ ems_demand_record upsert                  （每槽位一条留痕）
   └→ Kafka iot-thing-event(demandOverLimit)    （告警中心规则触发）
```

## 节省估算 + 告警

### `DemandSavingsEstimator`（纯函数，接入 demandSavings）

某周期（日/月/年）内，从 `ems_demand_record` 聚合：
- **实际最大需量** = max(各槽位 `demand_kw`)；
- **未削峰最大需量** = max(各槽位 `demand_kw + shaved_kw`)（削峰时放掉的功率加回，估算无电池时需量）；
- **节省金额** = (未削峰最大需量 − 实际最大需量) × `demand_rate` × 期数系数（月=×1，年=×12，日=×1/30 为估算示意）。

`EmsRevenueService.demandSavings` 从硬编码 `0` 改为调估算器（无配置/无记录 → 0，不破坏 P1-1 契约）。

### `DemandAlarmProducer`

- 复用 `EmsKafkaProducer.send(topic, key, value)`，发布 `ThingEventMessage`（`eventName="demandOverLimit"`，`severity` 按超限比例分级，`data` 含 `{demandKw, limitKw, stationId}`）到 Kafka `iot-thing-event`。
- energy-alarm 消费 → 匹配事件规则（`trigger_type=2`）→ 告警中心可见。平台无种子规则，需用户在告警中心自行建规则（设计内不建种子）。
- 不超限不发布；同一槽位已发布过则不重复（幂等）。

## API（`DemandController` `/ems/demand`）

| 方法 | 用途 |
|---|---|
| `GET /ems/demand/records?stationId&date` | 槽位记录（曲线 + 超限明细） |
| `GET /ems/demand/config?stationId` | 需量配置 |
| `PUT /ems/demand/config` | 配置 upsert（限值/费率） |
| `GET /ems/demand/savings?stationId&periodType&date` | 节省估算（summary 的 demandSavings 一并更新） |

## 前端需量管理页 `/ems/demand`

仿 EmsRevenue.vue 结构（ECharts + KPI 卡片 + el-dialog）：
- 筛选：电站 + 日期/周期（日/月/年）；
- **需量曲线**：每槽位 15min 平均功率（柱/折线）+ 限值参考线（红色 dashed），超限槽位高亮；
- **超限明细表**：`over_limit=true` 槽位 → 时间窗 / 需量 / 限值 / 超限量 / 削峰功率 / 动作；
- **节省估算卡片**：实际最大需量 / 未削峰需量 / 节省金额；
- **需量配置弹窗**：`demand_limit_kw` + `demand_rate`（upsert 保存后刷新）；
- 路由 `ems/demand` + MainLayout EMS 侧边栏加「需量管理」。

## 后端模块划分（energy-ems，仿 P1-1 分层）

| 包 | 组件 |
|---|---|
| `web` | `DemandController` + DTO |
| `service` | `DemandDetectScheduler`（每min循环）、`DemandShaveClient`（削峰下发封装）、`DemandAlarmProducer`（事件发布） |
| `util` | `DemandDetector`（纯函数：槽位均值/超限判定/削峰功率）、`DemandSavingsEstimator`（纯函数） |
| `mapper`/`entity` | `EmsDemandConfig`、`EmsDemandRecord` + `MeterDeviceMapper`（新） |
| 复用 | `TsdbClient`、`CommandClient`、`EmsKafkaProducer` |

## 测试

- **纯函数 TDD**：`DemandDetectorTest`（槽位均值 / 超限边界 / 削峰功率钳制 / 无 PCS→ALARM_ONLY / dispatch 失败→SHED_FAILED）、`DemandSavingsEstimatorTest`（加回 shaved_kw / 无记录→0 / 期数系数）。
- **服务层**：`DemandShaveClientTest`（dispatch 成功/失败→记录 action）、config/record upsert 幂等。
- **全量回归**：后端 `mvn -pl energy-ems test`；前端 `npx vue-tsc --noEmit` + `npm run build` + `npx vitest run`。

## 非目标（YAGNI）

- 不做严格滑动窗口（用固定 15min 槽位）；不做 Kafka 流式检测。
- 不做告警规则种子（用户告警中心自建）。
- 不做需量费率多档/尖峰费率阶梯（单费率够用）。
- 不做削峰 ACK 状态机（削峰是即时指令，非计划点）。
- 不做超限后自动恢复充电逻辑（削峰窗口结束自然恢复）。
- 不改 `PlanGenerator` 的 DEMAND 分支（`demandLimit` 字段继续保留，检测循环独立消费站点配置）。

## 风险与遗留

- **电表遥测依赖**：需量检测与削峰正确性依赖电表 `importPower` 遥测持续上报；模拟验证需让模拟器上报电表功率曲线。
- **告警链路依赖用户建规则**：事件照发，但告警中心可见性依赖用户在规则页配置 `trigger_type=2` 事件规则。
- **节省估算口径**：未削峰需量通过「加回 shaved_kw」估算，非真实无电池场景；日视图期数系数 ×1/30 为示意。
- **削峰指令无 ACK 复核**：即时指令不追踪执行结果（不同于计划点）；后续如需可加。
