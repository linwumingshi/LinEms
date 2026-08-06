# Phase 6 · 业务模块（影子 / 指令 / 告警）— 设计说明

> 版本：v1.0 ｜ 日期：2026-08-06 ｜ 阶段：业务模块（6a 影子 → 6b 指令 → 6c 告警）
> 上游依赖：Phase 1 §7（Kafka 15 topic）、Phase 2（MySQL 分域 es_shadow/es_command/es_alarm + Redis key 规范）、Phase 5（标准化属性/事件/生命周期 topic 契约）
> 验收对照：Phase 1 §13（控制 P99 ≤500ms、告警检测延迟 ≤3s、命令/告警全链路幂等）

---

## 1. 设计说明

本阶段交付三个业务微服务，把「设备面数据」转化为「可运营的业务能力」：

| 服务 | 端口 | 消费 | 职责 |
| --- | --- | --- | --- |
| **energy-shadow**（6a） | 8113 | iot-thing-property | 设备影子：reported/desired 双写，delta 差异发布，期望值下发桥接 |
| **energy-command**（6b） | 8114 | iot-command-ack / iot-shadow-delta / iot-device-lifecycle | 指令中心：状态机收敛、在线直发/离线入队、ACK 超时重试 |
| **energy-alarm**（6c） | 8115 | iot-thing-property / iot-thing-event | 告警中心：规则引擎、持续窗口、静默防抖、恢复、查询/确认 |

> 端口说明：shadow/command/alarm 初版为 8103/8104/8105，与 Phase 3 业务服务（device/station 同端口）冲突；
> Phase 8 全栈联调时统一调整为 8113/8114/8115（见 `deploy/scripts/start-stack.sh` 端口表）。

三者共享 energy-common（KafkaConsumerEngine、消息 DTO、雪花 ID、Result），并新增 `AlarmMessage` 消息契约。

### 1.1 模块数据流（对齐 Phase 5 的 topic 边界）

```
上行（已标准化）：
iot-thing-property (key=deviceId) ─┬─ energy-shadow  → Redis Hash 热路径 + MySQL iot_shadow 乐观锁
                                   │                  └─ desired≠reported → iot-shadow-delta
                                   └─ energy-alarm   → 属性规则（阈值 + 持续窗口 + 恢复）

iot-thing-event   (key=deviceId) ──→ energy-alarm    → 事件规则（标识匹配 + 静默）

iot-command-ack   (key=commandId) ─→ energy-command  → 状态机 CREATED→…→SUCCESS/FAILED/TIMEOUT

iot-shadow-delta  (key=deviceId) ──→ energy-command  → 物化为 setProperties 指令

iot-device-lifecycle (ONLINE) ─────→ energy-command  → 补发离线队列 iot:cmd:q:{deviceId}

下行：
energy-command → iot-command-down (key=deviceId) → energy-access → Broker → 设备

发布：
energy-alarm → iot-alarm (key=deviceId) + WebSocket /ws/alarm + ES es-alarm-log-{yyyyMM}
```

### 1.2 容量与延迟目标对应

| 指标 | 目标 | 落地 |
| --- | --- | --- |
| 控制 P99 | ≤500ms | 在线直发走 Kafka（关键路径零 DB 写后置读，落库即直发）；离线入队 O(1) LPUSH |
| 告警检测延迟 | ≤3s | 属性规则消费分区并行 + 规则纯内存计算（无 DB 查询）；持续窗口/静默为 Redis 常量级 |
| 命令/告警幂等 | at-least-once | 见 §5 幂等矩阵：状态条件更新 / 雪花主键 / SETNX 静默 |
| 告警风暴抑制 | — | 持续窗口（毛刺）+ 静默期（防抖合并）+ 记录按月分区 + ES 冗余 |

---

## 2. 技术决策（含理由）

### D1. 影子 reported 双写：Redis Hash 热路径 + MySQL 乐观锁（权威源）
属性上报 20 万~50 万 msg/s，读多写少场景选 Redis Hash 承载实时查询；但 Redis 有丢失窗口（崩溃/驱逐），故 MySQL `iot_shadow` 为权威源。**乐观锁更新**（`UPDATE ... SET version=version+1 WHERE device_id=? AND version=?`）保证并发 merge 不互相覆盖；0 行影响重试（最多 3 次），耗尽抛 IllegalStateException。

### D2. 影子不做消息级去重（与 access/tsdb 不同）
属性合并天然幂等：重复消息只是重复 merge，幂等由「合并 + 乐观锁」保证。若在消费边界加 SETNX 去重，Kafka 重放时**可能丢弃真正新的上报**，反而破坏收敛——重放 self-heal 部分失败正是期望行为。

### D3. delta 差异物化为 iot_command（不直接生产 set 指令）
影子只发布差异 `iot-shadow-delta`；具体下发由指令中心**物化为标准 `setProperties` 指令**（走 iot_command 状态机，ACK 可追踪）。避免产生「无 commandId 的裸下行」，ACK 无法归因。物化前做**在途合并**：同设备存在 state∈(1,2,3) 的 setProperties 则跳过（shadow 收敛后再发新 delta）。

### D4. 指令 ACK 幂等靠「状态条件更新」，不设 MessageDedup
`UPDATE ... SET state=? WHERE command_id=? AND state IN (合法前驱)`。重复 ACK / Kafka 重放时，目标状态不在合法前驱集合 → 0 行影响 → 空操作。终态无出边，天然忽略后续 ACK。相比「去重 + 直接更新」，条件更新让**重放路径与正常路径完全同构**，无需额外状态。

### D5. commandId 即幂等键（创建幂等）+ 补发仅当 state=0
创建指令：客户端可携带 commandId（缺省服务端雪花生成），`IdempotencyUtils` SETNX 24h 防重复创建。下发/离线补发均以 `WHERE state=0` 守卫，避免同一指令被两条路径重复投递。

### D6. 离线指令「队列入队保持 CREATED」，上线补发；超时重试按 sent_time 锚定
离线不置 SENT 而是留在 CREATED + Redis List（`iot:cmd:q:{deviceId}`，TTL 7d 上限 500），设备上线（lifecycle ONLINE）补发。超时扫描以 `sent_time`（非 create_time）为 deadline 锚点：每次重发重置时钟；重试耗尽置 TIMEOUT 终态。**崩溃窗口**：LPOP → updateSent(commit intent) → send，崩在 updateSent 后由超时扫描兜底；崩在 LPOP 前保持 CREATED，下次上线重放。

### D7. 影子 delta / 告警 product 解析的跨 schema 查询
`iot_command` 无 device_name、`iot_alarm_rule` 按 product_id 作用域而消息只带 product_key——两个服务均以**单 DataSource + 全限定表名**跨库读 `es_device.iot_device` / `es_product.iot_product`，不建冗余副本（Phase 2 分域边界，ADR-006）。告警侧对 product_key→product_id 做带 TTL 的本地缓存，避免每消息一次 DB 查询。

### D8. 告警「持续窗口」用 Redis 时间戳，而非状态机
属性规则抑制瞬时毛刺：首次违反 SET 时间戳（`alarm:sustain:{ruleId}:{deviceId}`），连续超阈满 windowSec 才算告警；值回正常即删除。不引入每规则的持久状态，Redis 常量级读写支撑高吞吐。windowSec=0/缺省=立即触发。

### D9. 告警静默防抖用 SETNX + TTL
触发后 `SETNX alarm:silence:{ruleId}:{deviceId}`（TTL=静默期），静默期内同规则不重复记录，合并为一条事件流；恢复时清除静默键，允许下次越阈重新告警。SETNX 原子性保证**多实例/重放下同规则+设备最多一条**，配合雪花 alarm_event_id 主键，告警写入天然幂等。

### D10. 告警发布三路「尽力而为」解耦
记录落库（MySQL，权威源）后并行发布：Kafka iot-alarm（业务总线）、WebSocket /ws/alarm（驾驶舱实时）、ES es-alarm-log-{yyyyMM}（检索冗余）。三路各自失败互不牵连、不回滚落库——ES 写入失败只记日志，靠 MySQL 保数据不丢。

---

## 3. 项目目录结构

```text
backend/
├── energy-shadow/          # 影子服务（8113）
│   └── src/main/java/com/sanduo/energy/shadow/
│       ├── ShadowApplication.java
│       ├── config/  ShadowProperties.java   ShadowConsumerConfig.java
│       ├── consumer/ PropertyShadowConsumer.java
│       ├── mapper/  ShadowMapper.java       ShadowHistoryMapper.java
│       ├── model/   ShadowRow.java          ShadowHistoryRow.java
│       ├── mqtt/    ShadowKafkaProducer.java
│       ├── service/ ShadowService.java      DeltaCalculator.java
│       ├── util/    ShadowRedisKeys.java
│       └── web/     ShadowController.java   dto/ShadowView.java  dto/DesiredRequest.java
│       └── test/    delta/DeltaCalculatorTest.java   service/ShadowServiceTest.java
│
├── energy-command/         # 指令中心（8114）
│   └── src/main/java/com/sanduo/energy/command/
│       ├── CommandApplication.java
│       ├── config/  CommandProperties.java  CommandConsumerConfig.java
│       ├── consumer/ AckCommandConsumer.java  DeltaCommandConsumer.java  LifecycleCommandConsumer.java
│       ├── mapper/  CommandMapper.java      CommandAckMapper.java      DeviceInfoMapper.java
│       ├── model/   CommandRow.java         DeviceInfo.java
│       ├── mqtt/    CommandKafkaProducer.java
│       ├── scheduler/ CommandTimeoutScanner.java
│       ├── service/ CommandService.java
│       ├── state/   CommandState.java
│       ├── util/    CommandRedisKeys.java
│       └── web/     CommandController.java  dto/CreateCommandRequest.java  dto/CommandView.java
│       └── test/    state/CommandStateTest.java   service/CommandServiceTest.java
│
├── energy-alarm/           # 告警中心（8115）
│   └── src/main/java/com/sanduo/energy/alarm/
│       ├── AlarmApplication.java
│       ├── config/  AlarmProperties.java    AlarmConsumerConfig.java
│       ├── consumer/ PropertyAlarmConsumer.java  EventAlarmConsumer.java
│       ├── engine/  AlarmRuleEngine.java
│       ├── es/      AlarmEsWriter.java
│       ├── mapper/  AlarmRuleMapper.java    AlarmRecordMapper.java    ProductInfoMapper.java
│       ├── model/   AlarmRuleRow.java       AlarmRecordRow.java       AlarmCondition.java
│       ├── mqtt/    AlarmKafkaProducer.java
│       ├── service/ AlarmService.java       AlarmKafkaPublisher.java
│       ├── util/    AlarmRedisKeys.java
│       ├── ws/      AlarmWebSocketHandler.java  AlarmWebSocketConfig.java
│       └── web/     AlarmController.java    dto/AlarmRecordView.java  dto/AlarmAckRequest.java
│       └── test/    engine/AlarmRuleEngineTest.java   service/AlarmServiceTest.java
│
└── energy-common/  + message/AlarmMessage.java（告警消息契约，Kafka iot-alarm）
```

---

## 4. 核心代码设计

### 4.1 影子：reported 双写 + desired 合并 + delta（energy-shadow）

```java
// ShadowService.applyReported —— 属性上报
ReportedResult rr = upsertReported(deviceId, tenantId, props);   // 乐观锁合并，重试≤3
writeReportedRedis(deviceId, props, rr.reportedJson());          // Hash 热路径
maybeWriteHistory(deviceId, tenantId, rr);                       // 60s/设备节流写历史

// ShadowService.setDesired —— 期望设置（乐观锁 + delta 检测）
DesiredResult dr = upsertDesired(...);
Map delta = DeltaCalculator.compute(desired, readReportedRedis(deviceId));
if (!delta.isEmpty()) {
    publishDelta(deviceId, tenantId, version, delta);            // → iot-shadow-delta
}
```

- Redis：`iot:shadow:reported:{id}` / `iot:shadow:desired:{id}` Hash，TTL 7d；`iot:shadow:delta:{id}` TTL 30s。
- 关键陷阱已修复：Spring Data Redis 3.x `opsForHash()` 泛型为 `<K,Object,Object>`，`entries()` 返回 `Map<Object,Object>`；Hash 每字段存的是属性**单值 JSON**，需 `readValue(json, Object.class)` 解析（失败原样返回字符串，不丢值）。

### 4.2 指令：状态机 + 在线/离线分流 + 超时扫描（energy-command）

```java
// 状态机（CommandState）：0 CREATED → 1 SENT → 2 DEVICE_RECEIVED → 3 EXECUTING → 4 SUCCESS/5 FAILED/6 TIMEOUT
// ACK 转移表：CREATED/SENT → {2,3,4,5,6}；DEVICE_RECEIVED → {3,4,5,6}；EXECUTING → {4,5,6}；终态无出边

// 创建（createCommand）：commandId 幂等 → 解析设备 → INSERT(CREATED) → dispatch
// dispatch：在线 → producer.send(iot-command-down) + updateSent(0→1) + markInflight
//           离线 → rightPush(iot:cmd:q) 保持 CREATED（超限丢最旧，TTL 7d）
// applyAck：fromAckStatus → selectById → isAllowedAck → 条件更新（WHERE state IN 合法前驱）
//            终态 → clearInflight + persistAck
// drainOfflineQueue（lifecycle ONLINE）：LPOP → updateSent(仅当 state=0) → 原样重发
// timeoutScan（@Scheduled 5s）：retryCount<maxRetry → 在线 resendOnline 重发 / 离线 requeue 重入队
//                               重试耗尽 → markTerminalTimeout(state=6, 'ACK_TIMEOUT')
```

Redis 键（对齐 Phase 2 Redis-key规范 §3.3）：`iot:online:{id}`（Broker 维护）、`iot:cmd:q:{id}`（List，7d，cap 500）、`iot:cmd:inflight:{id}`（Hash，5min）、`iot:cmd:idem:{commandId}`（SETNX，24h）。

### 4.3 告警：规则引擎 + 三阶段检测（energy-alarm）

```java
// handlePropertyReport（属性规则）
for (rule : ruleCache) {                        // 启用规则全量缓存，@Scheduled 30s 刷新
    if (!matchesRule(rule, tenantId, deviceId, productId)) continue;  // 全局/产品/设备作用域
    value = msg.properties.get(condition.metric);
    if (engine.propertyMet(condition, value)) {
        if (!isSustained(rule, deviceId, windowSec)) continue;  // ① 持续窗口（Redis 时间戳）
        if (isSilenced(rule, deviceId)) continue;               // ② 静默防抖（SETNX）
        fire(...);                                              // ③ 落库 + 发布 + 静默标记
    } else {
        resetSustain(...);
        tryRecover(...);   // 显式 recovery 命中 / 无条件时条件不再满足 → 批量恢复 + RECOVERED
    }
}
// handleEventReport（事件规则）：eventMet 精确匹配 + 静默防抖，级别取事件 severity
```

- 规则 JSON（对齐 Phase 2 DDL）：属性 `{"metric":"temp","op":"GTE","value":60,"windowSec":60}`；事件 `{"event":"bmsFault"}`；恢复 `{"metric":"temp","op":"LT","value":55}`。
- `AlarmRuleEngine` 为**纯函数**（无状态无 IO）：数值优先比较，非数值 EQ/NEQ 走字符串，全可单测。
- 记录表 `iot_alarm_record` 按月分区（PK `(alarm_event_id, triggered_time)`），`status` 0 触发中/1 已恢复/2 已确认；恢复/确认用条件更新幂等。

### 4.4 跨模块复用（energy-common）

- `AlarmMessage`：告警消息契约（alarm_event_id/tenant_id/device_id/product_key/rule_id/rule_code/level/type/status/message/ext/ts），Kafka iot-alarm key=deviceId。
- `KafkaConsumerEngine`：N 线程同组消费、分区内保序、手动提交、失败进 DLQ。
- `SnowflakeIdGenerator` / `IdempotencyUtils` / `Result` 复用，三个服务参数一致（idempotent producer：enable.idempotence + acks=all）。

---

## 5. 可靠性设计

### 5.1 幂等矩阵

| 操作 | 幂等机制 | 重放效果 |
| --- | --- | --- |
| 影子 reported 合并 | 合并 + 乐观锁（version 条件更新） | 重复 merge，收敛一致 |
| 影子 desired 设置 | 乐观锁 + delta 幂等发布 | 重复设置无副作用 |
| 指令创建 | commandId SETNX 24h | 返回既有指令 |
| 指令 ACK | 状态条件更新（WHERE state IN 合法前驱） | 空操作 |
| 离线补发 | 仅当 state=0 | 已 SENT 跳过 |
| 超时扫描 | 条件更新 + sent_time 锚点 | 幂等 |
| 告警触发 | 雪花主键 + 静默 SETNX | 同规则+设备静默期内最多一条 |
| 告警恢复/确认 | WHERE status=0 / status!=2 条件更新 | 重复请求空操作 |

### 5.2 故障与退化

| 故障 | 行为 |
| --- | --- |
| Kafka 不可达 | 消费线程重试不退出（daemon），服务先启动；生产者 send 失败记日志 |
| MySQL 不可达 | 服务启动（initialization-fail-timeout:-1）；命令 ACK 消费不阻塞；告警规则缓存等下次刷新 |
| Redis 不可达 | 影子降级 DB 直读；命令离线队列/在途标记失败仅降级；告警持续窗口判不满足（不误报） |
| 告警规则解析失败 | 单规则 try/catch 隔离，不影响其他规则；坏 JSON 记日志 |
| ES 写入失败 | 仅告警日志冗余丢失，权威源 MySQL 不丢 |

### 5.3 告警三阶段时序（防误报/风暴）

```
temp=80 ──────────────────────── windowSec=60 ────────────────► 触发（SAT）
  │ 首次违反 SET sustain:1:100   │ 持续超阈满窗口 → fire（静默 SETNX）
  │                              │
  └── temp 回落 50 → delete sustain → recovery（无条件恢复：条件不再满足）→ 记录置 1 + RECOVERED
```

---

## 6. 测试方案

| 测试集 | 覆盖 | 数量 |
| --- | --- | --- |
| DeltaCalculatorTest | 差异计算（新增/变更/删除/空） | 6 |
| ShadowServiceTest | reported 双写、desired+delta、幂等、Redis 降级 | 8 |
| CommandStateTest | 状态机转移/终态/ACK 映射/非法转移 | 5 |
| CommandServiceTest | 在线直发/离线入队/幂等命中/失败释放/ACK 状态机/delta 物化/离线补发/超时重试/耗尽终态 | 13 |
| AlarmRuleEngineTest | 阈值比较边界/字符串比较/事件匹配/恢复条件/未知 op | 9 |
| AlarmServiceTest | 持续窗口判定/静默跳过/恢复/事件级别/产品作用域/分页/ACK 幂等 | 9 |

> 全部为纯单元测试（Mock Mapper/Redis/Kafka/WebSocket，真实 ObjectMapper/雪花/配置），无需中间件即可运行。Phase 8 统一做链路集成与压测。

### 构建命令（本机约束）

```bash
# Git Bash 中后台运行（PowerShell 编码问题规避）
mvn -Dmaven.repo.local="/d/Program Files/maven-repo" -pl energy-alarm -am test
# 全工程构建
mvn -Dmaven.repo.local="/d/Program Files/maven-repo" test
```

---

## 7. 下一阶段任务（Phase 6d · 收尾 → Phase 7 前端）

1. **Phase 6d 收尾**：本设计文档落盘、Redis-key规范补登告警键、README 阶段状态更新、全工程 12 模块构建测试。
2. **Phase 7 前端**：Vue3 驾驶舱（设备监控/影子/指令下发/告警中心），对接网关 + /ws/alarm 实时告警。

---

## 8. 本阶段验收自评（对照 Phase 1 §13）

- ✅ 影子：reported/desired 双写、乐观锁、delta 发布（ADR-005/007）
- ✅ 指令：7 态状态机、在线直发/离线队列、ACK 条件更新幂等、超时扫描重试（ADR-009）
- ✅ 告警：规则引擎、持续窗口、静默防抖、恢复、按月分区记录 + ES 冗余
- ✅ 控制 P99≤500ms：在线直发 Kafka 关键路径无 DB 读（落库后按 deviceId 直发）
- ✅ 告警检测≤3s：分区并行 + 规则纯内存 + Redis 常量级状态
- ✅ 幂等矩阵完整覆盖，重放语义 at-least-once
- ✅ 全模块纯单元测试 50 个（6a 14 + 6b 18 + 6c 18），无需中间件
