# 需量管理（P1-2）使用手册

> 版本：2026-08-11 · 配套代码：energy-ems P1-2 需量管理（commit 范围 `f941754..35edb77`）
> 设计文档：`docs/superpowers/specs/2026-08-11-ems-demand-management-design.md`

## 一、这是什么

需量管理解决「基本电费」（按最大需量计费）的省钱问题：储能电站在**用电尖峰**时段放电削峰，把电网侧最大需量压下去，从而少交基本电费。

本功能提供三个能力：

1. **需量检测** —— 每 15 分钟一个固定槽位，槽位需量 = 槽内遥测样本均值（一天 96 个槽位）
2. **超限削峰** —— 需量超过配置限值时，实时向 PCS 下发储能放电指令，把超出的部分补上
3. **节省估算** —— 估算「不削峰会是多少 vs 削峰后省了多少基本电费」

## 二、前置条件（数据来源）

| 条件 | 说明 |
|---|---|
| **METER 设备** | 表计设备，`device_type='METER'`、productKey=`snd_ess_meter`、**`station_id`=所选电站**、状态 2/3（在线），上报 `importPower`（用电功率）。`V2__seed_meter.sql` 只种子产品+物模型，**设备行需自行注册并挂到电站**（见 §7.1） |
| **PCS 设备** | 储能变流器，productKey=`snd_ess_pcs`、**`station_id`=所选电站**、状态 2/3（在线）。**无在线 PCS 时只告警不削峰**（记录 `ALARM_ONLY`） |
| **需量限值** | 需在页面/接口配置，**> 0 才启用检测**；未配置或 ≤ 0 直接跳过该电站 |
| **调度服务** | energy-ems 需正常运行（每分钟检测由内部调度器执行） |

## 三、怎么用（前端页面「需量管理」）

入口：左侧菜单 **EMS → 需量管理**（路由 `/ems/demand`）。

### 1. 配置限值和费率

点右上角「**需量配置**」弹窗：

- **需量限值 (kW)** —— 必填，> 0。电网需量超过该值即触发削峰/告警。保存后下一分钟检测生效
- **需量费率 (元/kW·月)** —— 基本电费单价，用于节省估算；可为空

点「保存」即 upsert 生效（重复保存覆盖）。

### 2. 筛选查看

- **电站** —— 下拉选择；清空则页面回到空态
- **周期** —— 日 / 月 / 年。⚠️ **只影响「需量节省」的聚合口径**；曲线与超限明细始终按**日**取 96 槽位
- **日期** —— 随周期切换粒度（日 / 月 / 年选择器）

### 3. 读面板

- **KPI 卡片**：实际最大需量（kW）、未削峰需量（kW）、需量节省（元）、超限槽位数、需量限值（kW）
- **曲线图**：96 根柱 = 每个 15 分钟槽位的需量。蓝色正常，**红色 = 超限槽位**；红色虚线 = 限值参考线
- **超限明细表**：时间窗、需量、限值、超限量、削峰量、动作标签：

  | 动作 | 含义 |
  |---|---|
  | `NONE` | 未超限（槽位定型） |
  | `SHED` | 已削峰（PCS 下发成功） |
  | `SHED_FAILED` | 削峰失败（部分设备下发异常，保留削峰意图功率） |
  | `ALARM_ONLY` | 仅告警（无在线 PCS） |

### 4. 节省估算口径

- 实际最大需量 = `max(各槽位 demand_kw)`
- 未削峰最大需量 = `max(demand_kw + shaved_kw)`（把削峰放掉的功率加回，估算无电池场景）
- 节省金额 = `(未削峰 − 实际) × 费率 × 期数系数`（**日 ×1/30 示意、月 ×1、年 ×12**）
- 无记录或费率 0 → 0；结果恒非负

## 四、后台机制（每分钟自动运行）

1. **每分钟**调度器（`DemandDetectScheduler`，cron `0 * * * * *`）触发一次，`DistributedLock`（key `scheduled:ems-demand-detect`，TTL 60s）防多实例重入
2. 遍历所有已配置限值（> 0）的电站，逐站独立处理（单站异常不中断其他站）：
   - 查在线 METER → 读 TSDB 当前 15 分钟槽位内遥测 → 算槽位均值 `slotAvg`
   - 槽位均值 **超限** → 削峰：
     - 削峰功率 = 超限量，等分给每台在线 PCS，下发 `DISCHARGE`（参数 `{action, power, socTarget, time}`；socTarget 取影子 SOC，取不到回退 30%；createBy=0）
     - 写记录 `action=SHED`（全部下发成功）/ `SHED_FAILED`（有失败，仍保留削峰意图功率）
   - **槽位首超**（该槽位第一次超限）→ 额外发布一条 Kafka `iot-thing-event` 事件 `demandOverLimit`：
     - `messageId = demand-{stationId}-{windowStart}`（按站+槽位幂等去重，同槽位后续分钟不再重复告警）
     - `severity`：超限比例 ≥ 1.2 → 3（严重），否则 2（一般）
     - `data`：`{demandKw, limitKw, stationId}`；`code = DEMAND_OVER_LIMIT`
     - 发布失败仅 log，不抛（不中断检测）
   - **未超限** → 写记录 `action=NONE`，定型当前槽位
3. 记录按 `station_id + window_start` upsert 定型（`ems_demand_record` 唯一键），后续分钟更新同槽位

## 五、对外接口（网关 `/api/ems/demand/**`）

| 接口 | 用途 | 说明 |
|---|---|---|
| `GET /api/ems/demand/records?stationId&date` | 某日 96 槽位需量记录（升序） | `date` 为 yyyy-MM-dd |
| `GET /api/ems/demand/config?stationId` | 电站需量配置 | 未配置返回 `null` |
| `PUT /api/ems/demand/config` | 保存配置（upsert） | body `{stationId, demandLimitKw, demandRate}`；`demandLimitKw` 必须 > 0，否则 `PARAM_INVALID` |
| `GET /api/ems/demand/savings?stationId&periodType&date` | 节省估算视图 | `periodType` = DAY/MONTH/YEAR |

另：`/api/ems/revenue/summary` 的 `demandSavings` 字段已接入真实估算（不再恒 0），收益核算页可直接查看需量节省。

## 六、数据模型速记

- **`ems_demand_config`** —— 每租户每站一条（唯一键 `tenant_id + station_id`），存 `demand_limit_kw`（限值）、`demand_rate`（费率）
- **`ems_demand_record`** —— 每站每天 96 条（唯一键 `station_id + window_start`），存 `demand_kw`（槽位实际需量）、`limit_kw`（限值快照）、`over_limit`、`shaved_kw`（削峰量）、`action`

## 七、验收 / 联调步骤

1. 重启 `energy-ems`（Flyway 应用 `V6__demand.sql`）、重启 `energy-product`（Flyway 应用 `V2__seed_meter.sql`）
2. 模拟器按 METER 设备上报 `importPower`（造峰值，如 500 kW）
3. 页面配置限值（如 300 kW）→ 下一分钟调度器检测 → 产生超限记录（`over_limit=1`、`action=SHED`），Kafka `iot-thing-event` 出现 `demandOverLimit`
4. 验证：
   - `GET /api/ems/demand/records` 返回 96 槽位；超限槽位在页面曲线红色高亮、明细表可见
   - `GET /api/ems/demand/savings` 返回节省金额
   - `/api/ems/revenue/summary` 的 `demandSavings` 非 0

### 7.1 用模拟器端到端模拟（推荐）

不需要真实表计/PCS，`test/sim-device` + `test/stress` 即可跑通「超限 → 削峰 → 告警 → 节省估算」全链路。构建方式见 `docs/sim-device-使用验证指南.md` §1.2。

**① 准备电站**（页面「电站管理」创建，或接口；记下返回的 <stationId>）：

```bash
# JWT 见 sim-device 指南 §4.1；enterpriseId=1 为系统种子企业 ENX-HQ
curl -s -X POST http://127.0.0.1:8000/api/station -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"stationCode":"SIM-DEMAND","stationName":"DEMAND-SIM","enterpriseId":1}'
# data = 电站ID
```

**② 造数注册 METER + PCS（都挂到该电站，序号避开默认 sim-dev-000001）**：

```bash
source deploy/env/local.env                       # 注入 MYSQL_PASSWORD（密钥外置）
java -jar test/stress/target/stress.jar seed --product snd_ess_meter --count 1 --start-index 2 --station <stationId> --tenant 1
#   → sim-dev-000002（METER @ <stationId>）
java -jar test/stress/target/stress.jar seed --product snd_ess_pcs   --count 1 --start-index 3 --station <stationId> --tenant 1
#   → sim-dev-000003（PCS @ <stationId>）
```

**③ 起模拟表计报尖峰**：

```bash
cd test/sim-device
./sim-device.sh --product snd_ess_meter --device sim-dev-000002
# sim-dev> report importPower=2500     # 造超限尖峰（> 限值）；TSDB 样本留存，槽位均值持续超限
```

**④ 起模拟 PCS（接收削峰指令；--autoack 自动回 SUCCESS）**：

```bash
./sim-device.sh --device sim-dev-000003 --autoack    # 默认 --product snd_ess_pcs
```

**⑤ 页面「需量管理」配置限值**（如 1000 kW、费率 40 元/kW·月）→ 保存。下一分钟调度器检测：
- 表计槽位均值 2500 > 1000 → 向 sim-dev-000003 下发 DISCHARGE 削峰指令 → autoack → `action=SHED`
- Kafka `iot-thing-event` 出现 `demandOverLimit`（messageId=`demand-{stationId}-{窗口}`，同槽位幂等只发一次）

**⑥ 验证**：曲线超限槽位红色高亮、明细表 `action=SHED`；`GET /api/ems/demand/savings?stationId&periodType=DAY&date` 返回节省金额；`/api/ems/revenue/summary` 的 `demandSavings` 非 0。

> **要点**：EMS 按 `es_device.iot_device.station_id` 查设备（不联 `iot_station_device` 关联表），seed 必须带
> `--station`；默认设备 `sim-dev-000001`（station_id=NULL）不会被任何电站查询命中，别拿它模拟本功能。

## 八、已知边界（使用注意）

- **无在线 PCS**：超限只告警不削峰（`ALARM_ONLY`），节省估算不受影响
- **超限后槽位内回落**：槽位已定型超限后，若均值回落到限值以下，该槽位的 `demand_kw` 定格在最后一次超限分钟的局部均值（不更新为全 15min 均值）——影响方向保守（节省估算略低），不影响告警/削峰
- **周期粒度**：月/年周期下曲线仍显示选中月/年第一天的 96 槽位，节省 KPI 则是整个周期的聚合值——两者口径不同属预期
- **告警幂等**：同一槽位只发一次 `demandOverLimit`，不会每分钟重复刷屏
- **费率**：未配置费率（null 或 0）时节省估算为 0，需量检测与削峰不受影响
