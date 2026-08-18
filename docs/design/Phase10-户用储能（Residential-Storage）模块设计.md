# Phase 10 · 户用/阳台储能模块设计（Residential & Balcony Storage）

> 版本：v0.2（按审查修订）｜ 日期：2026-08-13 ｜ 类型：架构草案 / 新模块规划
> 审查依据：`docs/review/2026-08-13-Phase10户用储能模块设计审查.md`（基于真实代码核对，证据均已标注 file:line）
> 背景：竞品分析（SAMDUO / Anker SOLIX / EcoFlow / Zendure / Marstek）显示，欧洲户用储能正从"C&I 工商业"向"家庭即插即用"下沉，核心能力是 **AC 即插即用接入 + 动态电价套利 + 自发自用/零馈网 + 开放 API**。本平台当前聚焦工商业（电站→柜→簇→PCS→BMS→电芯），本设计以**最小新增代码、最大化复用现有接入总线**的方式接入户用场景。
> 设计原则：**户用是平台的"资产子类 + 策略族"，不是独立系统。** 复用 mqttbroker / Kafka 总线 / TDengine / 设备影子 / 指令通道 / 多租户。

**修订记录（v0.1 → v0.2）**
- P0-1：`Station` 无 station_type 字段（`Station.java:54` 仅 `gridType`）→ 改为**扩展现有 `GridType` 枚举 +HOME**（§2.2）。
- P0-2：`DeviceType` 为 7 值固定枚举、物模型为整份 JSON Schema → 明确需**改枚举+字典**，测点以 `schemaJson` 承载，并**简化掉"逻辑子设备树"**（§2.2/§2.3）。
- P0-3：删除"连续查询降采样"表述（全仓无实现）→ 降采样列为**需新增项**（§5）。
- P1-1：明确 `energy-residential` 为 **Maven 库模块**，挂载现有服务（adapter→access、策略→ems），不新增微服务（§1/§3.1）。
- P1-2：内嵌 5 家竞品对照表（§9）。
- 新增设计缺口章节：本地优先/断网兜底（§4.2）、激活归属（§3.2）、规模与容量（§8）、C 端安全与 OTA（§10）、法规配置与租户语义（§11）。

---

## 1. 结论与定位

户用储能设备（户用一体机、阳台电池、P1 电表、智能插座）本质是**轻量版电站**：单点容量小（1~6kWh）、数量极大（一运营商管数万家庭）、控制逻辑本地优先。

**不新增微服务**（新代码以库模块挂载现有服务），而是：
1. 在资产模型扩展「家庭(Home)」场景与「户用一体机」设备类型，复用 `station` / `device` 全链路；
2. 在 EMS 扩展「住宅策略族」，复用 `PlanGenerator` + 安全包络校验 + PCS 下发 + ACK 状态机（挂载 `energy-ems`）；
3. 在接入层扩展户用 Profile 与 vendor adapter（挂载 `energy-access`），复用 access 标准化管线（白名单校验 / 幂等去重 / 留痕）。

这样平台从「工商业储能管理」升级为「**多租户储能运营商/聚合商底座**」，远期具备做 VPP（虚拟电厂）的潜力——这正是 C 端品牌（SAMDUO 等）缺的能力（VPP 聚合调度接口见 §8，属远期，不在本期范围）。

---

## 2. 资产模型扩展

### 2.1 现有层级（工商业，已实现）
```
集团(Group) → 企业(Enterprise) → 电站(Station) → 储能柜(Cabinet) → 电池簇(Cluster) → PCS → BMS → 电芯(Cell)
```

### 2.2 扩展方式（按代码现状）
代码事实（证据）：`Station.java:54` 仅有 `gridType` 字段，枚举 `GridType.java:12` = `COMMERCIAL_INDUSTRIAL / PARK / GRID`；`DeviceType.java:12-13` 为 7 值固定枚举，`Device.deviceType`（`Device.java:50`）为枚举类型，`Product.deviceType`（`Product.java:37`）为 String。

- **场景标识**：扩展现有 `GridType` 枚举新增 `HOME`（不改表结构，SQL 字典同步），家庭电站 `grid_type=HOME`；
- **设备类型**：`DeviceType` 枚举新增 `RESIDENTIAL_AIO`（户用一体机）+ `SMART_METER`（户用电表，或复用现有计量类）+ `SMART_LOAD`（智能负载）；需改枚举 + 字典；`Product` 侧 `deviceType` 为 String，产品/物模型定义可先行落地；
- **一体机内部结构**：户用一体机 = **单设备注册**，内置 PCS/BMS/电芯以 **`schemaJson` 测点分组**承载（AC 双向功率、SOC、PV 输入、电网输入输出、零馈网使能、堆叠台数），**不建逻辑子设备树**——避免与现有 `ThingModel`（整份 JSON Schema 快照，`ThingModel.java:38`）粒度不匹配，也避免告警/影子建模过度膨胀；
- 多租户（MyBatis-Plus 行级隔离，`ConditionalTenantLineHandler`）天然支持「一运营商管多家庭」，无需改动。

### 2.3 物模型（product）户用 Profile
以 `schemaJson` 承载的测点分组（对标竞品卖点）：
```
group:grid      # ac_input_power / ac_output_power / pv_input_power / grid_export_power
group:battery   # soc / cell_voltage / cell_temp / cycle_count
group:control   # zero_feed_in_enabled / charge_rate_limit / discharge_rate_limit / expand_unit_count
group:meter     # p1_import_energy / p1_export_energy / total_load      （户用电表）
```
并网限值等法规约束见 §11（配置化，不写死在物模型）。

---

## 3. 接入层

### 3.1 设备接入（复用 mqttbroker + 新库模块）
- 户用一体机经**自研 MQTT Broker** 接入，沿用 `mqtt.uplink` / `mqtt.down.{nodeId}` / `mqtt.broadcast` 三通道（`KafkaTopicConstant.java:12-66`）；
- **`energy-residential` 定位**：Maven 库模块（非微服务）——
  - `adapter` 包 → 挂载 `energy-access`：厂商私有物模型 → 标准户用 Profile 映射（多数 C 端机型本地走 MQTT/REST，如 SAMDUO 暴露 RESTful/MQTT）；
  - `strategy` 包 → 挂载 `energy-ems`：住宅策略族（见 §4）；
- 电表(P1)数据经 broker 或边缘网关采集（CT clamps / Shelly / HomeWizard P1 协议），进入同一标准化管线。

### 3.2 激活流程与归属（运营商为主）
- **主路径：运营商代激活 / 批量导入**——运营商在平台批量开户、录入设备 SN、预下发初始策略；设备配网后平台自动准入（复用设备状态机 `UNREGISTERED→INACTIVE→ONLINE`，`DeviceStatus.java:12-13`）；
- **辅助路径：户主扫码自助**（可做，作为二期）——扫码绑定家庭 → 配网 → 激活；
- **明确边界**：C 端家庭 App 不纳入本期范围（平台是 B 端底座，户主自助经运营商品牌 App 或 H5 对接，不在本期交付）。

---

## 4. EMS 住宅策略族（新增 strategy 类型）

复用 `PlanGenerator` 框架与"策略→计划→包络校验→下发→ACK→状态机"闭环。**新增三类住宅策略**（代码事实：现有 `StrategyType` = `PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME`，`isGeneratable()` 仅含前三者+时间，`DR/SOC_CTRL` 不产计划——住宅策略需真正实现 generator）：

| 策略 | 对标竞品能力 | 数据源 | 产出 |
|---|---|---|---|
| `RESIDENTIAL_DYNAMIC_TARIFF` 动态电价套利 | SAMDUO Intelligence / Zendure Nordpool | 动态电价 API + 天气预报 + 家庭 TOU | 24h 充放计划（低价充、高峰放） |
| `RESIDENTIAL_SELF_CONSUMPTION` 自发自用最大化 | 竞品"零馈网"叙事 | 光伏预测 + 负载曲线 | 日间存、晚间放，自用电率→90%+ |
| `RESIDENTIAL_PEAK_SHAVING` 削峰 | PowerMesh | 户用峰值负载 | 高峰放电削峰、降低需量 |

- 下发对象从"电站 PCS"变为"户用一体机内置 PCS"，复用同一指令通道（`iot-command-down`，`CommandService.java:311` 按 deviceId 定向）+ ACK 状态机（`CREATED→SENT→…→SUCCESS/FAILED`）；
- 安全包络校验复用（SOC 上下限、功率限值、温度），并新增**各国并网法规限值**（配置化，见 §11）作为包络硬约束。

### 4.1 外部数据源接入与降级（G7）
- **动态电价**：接北欧 Nordpool / 各国动态电价 API（EPEX/ENTSO-E）；
- **天气预报**：接开放天气 API（降水/辐照）做光伏与负荷预测；
- **降级策略**：电价/天气 API 拉取失败或超限时，回退到**最近一次可用电价曲线**或内置 TOU 模板生成计划，并在计划中标记 `source=degraded`；连续失败 N 次（如 3 次）停止生成、告警通知运营商，避免设备侧出现空计划。

### 4.2 本地优先 / 断网兜底（G1）
户用设备在家庭 Wi-Fi，云端断连时不能"停摆"（竞品均为本地 EMS 架构）。采用**双层控制**：
1. **云端**：生成 24h 计划模板（含策略参数、SOC 上下限、充放功率曲线）下发到设备端本地缓存；
2. **设备端**：按计划模板本地执行；云端断连时按**最近一次计划模板**或设备默认策略（低价充/高峰放）兜底运行，重连后回传执行记录；
3. 平台侧只负责"计划生成 + 配置下发 + 结果回收"，不参与秒级控制回路（也符合 Broker 线程铁律：不在控制回路里做云端依赖）。

---

## 5. 时序、影子与监控

- **TDengine**：复用超级表自动建表（`TdengineSqlBuilder.java:89` `st_prop_{productKey}`，TAGS=device_id/station_id/enterprise_id/product_key，子表 `dev_{deviceId}`），户用设备高频（per-min）遥测直接写入；
- **⚠️ 降采样为需新增项**：当前代码**无连续查询/降采样**（全仓无 `INTERVAL/CREATE CONTINUOUS`）。户用 per-min × 万家庭场景下为硬需求，建议扩展 `TdengineSqlBuilder` 生成 `CREATE CONTINUOUS QUERY`（分钟级→小时/日级聚合），或上游网关本地预聚合后再上报；
- **家庭级聚合视图**：新增"家庭能源"聚合（自用电占比、节省金额、CO₂ 减排），可由降采样结果支撑；
- **设备影子(shadow)**：复用 `desired/reported`（`ShadowRow.java:17-19`），`setDesired` 发布 delta（`ShadowService.java:94`）物化为 `setProperties` 指令——用于户用一体机远程参数下发；
- **告警(alarm)**：复用告警管线，户用新增"馈网超限 / 零馈网失效 / 电表失联 / 计划降级"等家庭侧告警。

---

## 6. 前端（家庭能源工作台）

复用 Vue3 + ECharts 驾驶舱组件，新增「家庭能源」页：
- 户主视角：自用电占比、年化节省、CO₂、24h 充放路线图（对标 SAMDUO Intelligence）、零馈网状态、设备健康；
- 运营商视角：多家庭聚合看板（接入数、总容量、总节省、异常家庭 TopN）——VPP 聚合入口（调度接口见 §8，远期）。

---

## 7. 开放生态（平台相对 C 端品牌的 B 端优势）

代码事实：`energy-gateway` 为独立 Spring Cloud Gateway（WebFlux），EMS 已纳入路由（`application.yml:64-69`），JWT 鉴权门卫 `GlobalAuthFilter`。

平台对外提供 RESTful/MQTT（经网关鉴权）给：
- 智能家居：Home Assistant / Homey（对标 SAMDUO RESTful/MQTT + HA 集成）；
- 第三方电表：Shelly / HomeWizard P1；
- 聚合商/虚拟电厂调度接口（远期，见 §8）。

这是平台切入"户用储能运营商/聚合商"角色的差异化利器。

---

## 8. 规模与容量假设（G3）

户用接入量与 C&I 不同：**量大、单点小、频次高**。需在立项时确认量级，并依赖阶段3 容量框架验证：

| 量级场景 | 遥测点/s 估算 | 压力点 |
|---|---|---|
| 1 万家庭（每户 1 一体机 + 1 电表，per-min 60 点/台） | ≈ 2 万点/s | TDengine 写入、Kafka 分区（`mqtt.uplink`） |
| 10 万家庭 | ≈ 20 万点/s | 同上 + 影子 Redis 热 key、网关路由 |

**应对**：① 上报粒度可配（per-min / 15-min / 5-min，默认 5-min）；② 边缘网关本地聚合后上报；③ 降采样落库（§5 新增项）；④ 阶段3 容量框架统一验证。**本期不承诺具体规模，先做量级假设 + 压测计划。**

---

## 9. 竞品启示（对照表）

| 维度 | **SAMDUO** Nex E6000 | **Anker SOLIX** Solarbank 2 E1600 AC | **EcoFlow** Stream (AC) | **Zendure** SolarFlow Hyper 2000 | **Marstek** B2500 / Venus E |
|---|---|---|---|---|---|
| 基础容量 | 6kWh（6→90kWh，≤15台） | 1.6kWh（+5电池） | 5kWh（Ultra X 扩 23kWh） | ~1kWh(AB1000)起 | 2.24kWh（扩 6.72）/ Venus E 5.12kWh |
| 功率 | 双向 2600W / 并网 800W | 并网 800W / 离网 1200W | 5000W 光伏输入 | 双向 1200W AC | 1000W PV / 800W 输出 |
| 耦合 | AC 即插即用 | AC 即插即用 | AC 即插即用 | AC 即插即用 | B2500 需微逆 / Venus E AC |
| 电芯·循环 | LiFePO₄·10000 | LFP·15 年寿命 | LFP | LFP·GaN | LFP·6000 |
| 质保 | 10 年（15 年寿命） | 10 年 | 未明示 | Ace 5 年 | 未明示 |
| 智能调度 | SAMDUO Intelligence（AI 24h）+ PowerMesh | App + Smart Meter | AI 驱动节省 | ZenLink + Nordpool 动态电价 | App 监控 |
| 开放生态 | RESTful/MQTT + HA/Homey | 未明示 | 未明示 | Shelly / 动态电价 | 否 |
| 主战场 | 荷兰（P1 电表） | 德国（VDE） | 全球 | 欧洲 | 欧洲 |
| 价格 | €1,999 | 未明示 | 未明示 | Hyper2000 €1,183 / Ace €499 | 未明示 |

**提炼**：共性卖点 = AC 即插即用免电工、超薄、动态电价套利+零馈网、开放 API 接智能家居；共同短板 = 无多家庭集中运营/聚合能力。**平台最大机会**：以多租户 + 自研 Broker 集群 + TDengine 切入"户用储能运营商/聚合商"角色。

---

## 10. C 端设备安全与 OTA（G4）

- **接入认证**：复用现有 HMAC-SHA256 + nonce 防重放认证与 Redis 封禁（Broker 已实现），户用设备不强制 mTLS（C 端配证体验差）；
- **证书/密钥生命周期（新设计点）**：若后续启用 mTLS，需新增"证书每台签发/轮换/吊销"管理——C 端规模化签发是新的运维负担，**建议本期不启用 mTLS，用 HMAC+nonce + 设备密钥分权**；
- **OTA 固件升级（新设计点）**：平台无 OTA 模块。建议**对接厂商 OTA 服务**（平台只做"设备固件版本档案 + 升级任务台账"），二期再考虑自建；
- **限流/风暴防护**：复用 Broker 认证/重连风暴信号量限流，户用批量上线场景需压测确认。

---

## 11. 法规配置化与租户语义（G5/G6）

### 11.1 并网法规配置化（G5）
- 新增配置表 `residential_grid_limit`（`country_code` / `limit_type` / `value` / `unit` / `effective_from`）；
- 包络硬约束从配置读取（如 `DE` 并网输出 ≤800W、`NL` P1 协议解析、`IT`/`FR` 待补）；
- 物模型不写死法规值，策略生成时按家庭 `country_code` 装配包络。

### 11.2 租户语义（G6）
- **租户（tenant_id）= 运营商（Aggregator）**，家庭是运营商下的资产节点，**不把家庭当租户**；
- 行级隔离天然生效（家庭、设备均带运营商 tenant_id）；
- 家庭与电站的关系：`station(grid_type=HOME)` 归属某运营商；户主身份与资产解耦（平台不建户主账号体系，户主经运营商 App/H5 间接访问）。

### 11.3 验收指标（G6）
| 指标 | 目标 |
|---|---|
| 家庭接入耗时（开户→在线） | ≤ 30s |
| 计划策略命中率（生成→ACK 成功） | ≥ 90% |
| 节省测算误差（预估 vs 实际电费） | ≤ 10% |
| 断网兜底：设备离线 ≥4h 后恢复，数据无丢失 | 100% |

---

## 12. 落地路线图

- **阶段 A（资产与接入）**：`GridType.HOME` + `DeviceType` 新增枚举/字典 + 户用 Profile（schemaJson）+ `energy-residential` 库模块 adapter 包（挂 access）+ 运营商代激活/批量导入。
- **阶段 B（住宅 EMS）**：三类住宅策略 generator（挂 ems）+ 动态电价/天气数据源与降级（§4.1）+ 法规限值配置表（§11.1）+ 计划模板下发与设备本地兜底（§4.2）。
- **阶段 C（监控与开放）**：家庭聚合驾驶舱 + 运营商多家庭看板 + 降采样落库（§5）+ 开放 API（Home Assistant 集成，经网关）。
- **阶段 D（打磨/远期）**：户主扫码自助激活、C 端 H5、VPP 聚合调度接口、OTA 对接。

---

## 13. 风险与待确认

- 住宅策略 generator 需从零实现（现有 DR/SOC_CTRL 不产计划，不能简单复制）；
- 动态电价/天气数据源版权与地域覆盖需商务确认；断连降级链路需压测；
- 各国并网法规需按 `country_code` 逐国补配置（先 DE/NL）；
- vendor adapter 需逐品牌对接，建议先打通 1 个标杆（如 SAMDUO 的 MQTT/REST）；
- 规模假设（§8）未验证，**不承诺具体接入量级**，依赖阶段3 容量框架；
- C 端安全：本期不做 mTLS 与自建 OTA（§10），需在商务/合规层面确认可接受。
