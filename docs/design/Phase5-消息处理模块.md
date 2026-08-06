# Phase 5 · 消息处理模块（接入适配 + 时序摄取）— 设计说明

> 版本：v1.0 ｜ 日期：2026-08-06 ｜ 阶段：消息处理
> 上游依赖：Phase 1 §7（Kafka 15 topic）、Phase 2 §4（TDengine 宽表/事件建模 + Redis key 规范）、Phase 4（Broker 的 mqtt.router 信封契约）
> 验收对照：Phase 1 §13（消息链路 20~50 万 msg/s、单设备保序、at-least-once + 幂等）

---

## 1. 设计说明

本阶段交付**消息处理模块**，承接 Phase 4 Broker 之后的所有设备面数据消费与标准化：把 Broker 投递到 `mqtt.router` 的原始信封，经**物模型校验 + 类型强转**清洗为标准化消息，再分流到属性/事件/指令 ACK/原始留痕/生命周期五类 topic；并新增**时序摄取服务**把标准化属性和事件批量写入 TDengine。

### 1.1 交付范围

| 交付物 | 说明 |
| --- | --- |
| energy-access 模块（8111） | 接入适配：上行解析/物模型校验/标准化、下行桥接、生命周期处理，约 28 个类 |
| energy-tsdb 模块（8112） | 时序摄取：属性宽表/事件批量落 TDengine，约 10 个类 |

> 端口说明：access/tsdb 初版分别为 8101/8102，与 Phase 3 业务服务（system/product 同端口）冲突；
> Phase 8 全栈联调时统一调整为 8111/8112（见 `deploy/scripts/start-stack.sh` 端口表）。
| energy-common 增量 | RouterEnvelope 契约迁移、6 个消息 DTO、KafkaConsumerEngine、MessageDedup、KafkaTopicConstant（16 topic） |
| 测试 | 新增 common 5 + access 14 + tsdb 13 = 32 个纯单元测试（全工程 9 模块构建通过） |

### 1.2 消息链路（本阶段覆盖的 Kafka 消费/生产边界）

```
设备 → Broker PUBLISH {pk}/{dn}/up/{type} → ACL → RouterEnvelope → mqtt.router（24 分区, key=topic）
  └─ energy-access（group=energy-access-uplink, 4 线程）
       ├─ 物模型校验 + 类型强转（未知 identifier 拒绝 / enum 越界拒绝 / required 缺失仅告警）
       ├─ iot-thing-property（key=deviceId, 保序）→ energy-tsdb → TDengine st_prop_{productKey}
       ├─ iot-thing-event                         → energy-tsdb → TDengine st_event（payload JSON）
       ├─ iot-command-ack（key=commandId）        → Phase 6 command 模块
       ├─ iot-raw（key=messageId）                → 原始报文留痕（追踪/补数）
       └─ iot-device-lifecycle                     → energy-access（group=energy-access-lifecycle）
             └─ 刷新 iot_device 在线态 + iot_device_online_record + 离线指令补发

下行：
Phase 6 command → iot-command-down（key=deviceId）
  └─ energy-access（group=energy-access-command-down）→ publishRouterDown
       └─ RouterEnvelope PUBLISH {pk}/{dn}/down/command → mqtt.router → Broker fan-out 投递设备
```

### 1.3 容量目标对应

| 指标 | 目标 | 本阶段落地 |
| --- | --- | --- |
| 上行吞吐 | 20 万~50 万 msg/s | 分区并行消费（N 线程×同组 consumer）+ 单分区单线程保序；消息体小、校验纯计算 |
| 时序写入 | 500 万 points/s | 批量缓冲（1000 行/2MB 阈值）+ 一语句多 INSERT 块 + TDengine 自动建子表 |
| 单设备保序 | 严格有序 | 标准化 topic 一律 key=deviceId ⇒ 同设备同一分区；消费端分区内单线程 |
| 交付语义 | at-least-once + 幂等 | 手动提交 + 每消费边界独立 SETNX 去重（stage=access/tsdb） |

---

## 2. 技术决策（含理由）

### D1. access 消费 mqtt.router，而非 Broker 直投标准化 topic
Broker 只负责「连接 + 路由」，把设备报文**原样**塞进 mqtt.router；标准化（物模型校验、类型强转、切 topic）全部收敛在 energy-access 一个消费边界。理由：① 避免 Broker 与业务耦合（Phase 4 D1 约束）；② 物模型版本演进只影响 access，不影响设备面；③ 所有设备消息只在 access 一处做一次「上行解析」，后续 tsdb/shadow/rule 直接消费已标准化的 topic，不做二次解析。

### D2. 每消费边界独立去重命名空间（stage），TTL 300s
一条设备报文会顺序经过多个 Kafka 边界（access 摄入 → tsdb 落库 → shadow/rule），每个边界的 Kafka 交付都可能各自重放。若共用同一 `SETNX key`，上游边界消费过一次，下游会把合法消息误判为重复而丢弃。因此 `iot:msg:dedup:{stage}:{device_id}:{message_id}` 中 **stage 按边界取值（access/tsdb/shadow/rule/...）**，各自独立 SETNX、独立 TTL。这是本阶段最关键的幂等设计（详见 §5.2）。

### D3. 物模型校验前置在 access（落库前清洗），白名单语义
- 未知 identifier → **拒绝**（防脏数据污染 TDengine 宽表/影子，对齐 ADR-008 白名单）；
- 类型强转失败 → 拒绝该属性（float 收到 "abc"）；
- enum 越界 → 拒绝该属性；合法 enum 值**归一为模型登记的规范值**（数值类型，字符串 "2" 无法写入 TDengine INT 列）；
- required 缺失 → **仅告警不拒绝**（储能设备常做部分上报，如 5s 只报 SOC，其余列为 NULL）；
- 拒绝不阻断整条链路：报文仍留痕 iot-raw（rejectReason），标准 topic 不产出，可追踪补数。

### D4. 原生 kafka-clients + 共享消费引擎，不引 spring-kafka
承接 Phase 3 D9（镜像缺 spring-boot-starter-kafka）。`KafkaConsumerEngine`（energy-common）封装「N 线程各持同组 consumer + 分区内单线程 + 手动提交 + 失败进 DLQ」，全模块复用：access 三组消费、tsdb 两组消费，线程模型透明可控。

### D5. TDengine 宽表列 = 物模型 identifier，写「消息携带的列」
对齐 Phase 2 `st_prop_{productKey}`（列 ts/msg_id/data_type + 物模型 identifier 列）。一条属性上报往往只带部分属性：**写入 SQL 只列消息实际携带的属性列**，缺省列由 TDengine 置 NULL。列名反引号包裹兼容保留字；非法标识符防御性跳过。事件表 `st_event` 的 `payload` 为 JSON 列，可变载荷直接序列化落列。

### D6. 批量缓冲 + 一语句多 INSERT 块
`TsdbBatchBuffer` 把多行 INSERT SQL 聚合成一条语句一次 execute（TDengine 支持一语句多 INSERT 块），达到行数（1000）/字节（2MB）阈值立即冲刷（消费线程内同步冲刷=天然背压）；冲刷失败把已取出行**回滚到队头保序重试**并上抛（当前记录进 DLQ），不丢数据；idle 时由 1s 调度兜底冲刷，控制摄取端到端延迟 ≤1s。

### D7. 下行复用 mqtt.router：access 做生产者，Broker fan-out 投设备
Phase 6 command 只写 `iot-command-down`（key=deviceId）；access 消费后把 CommandDownMessage 重新包成 RouterEnvelope PUBLISH 到 `{pk}/{dn}/down/command`，回到 mqtt.router，由每节点消费组全量 fan-out、按 ACL 订阅投递到设备。好处：下行与上行共用同一路由管道，设备只需订阅 `down/*` 一条通道，不需要了解平台内部 topic 布局。

### D8. 生命周期双通道 + 离线指令补发
- Broker 同时写 `iot:online`（Redis 心跳）与 `iot-device-lifecycle`（Kafka 事件），access 消费生命周期：ONLINE → `status=3` + broker_node + 上线记录 + 触发 `iot:cmd:q:{deviceId}` FIFO 补发；OFFLINE → `status=2` + `online_seconds` 累计 + 下线记录；
- 离线补发复用下行路径（D7）：从 Redis 队列 leftPop 逐条重新桥接，Phase 6 指令状态机在权威表收敛最终一致性。

---

## 3. 项目目录结构

```text
backend/
├── energy-common/                              # 共享契约（Phase 5 增量）
│   └── src/main/java/com/sanduo/energy/common/
│       ├── mqtt/        RouterEnvelope.java、MqttUpType.java、MqttTopicInfo.java、MqttTopicUtil.java
│       ├── message/     ThingPropertyMessage / ThingEventMessage / CommandAckMessage /
│       │                CommandDownMessage / LifecycleMessage / RawMessage（6 个 @Data DTO）
│       ├── kafka/       KafkaConsumerEngine.java、KafkaRecordHandler.java
│       ├── redis/       MessageDedup.java、IdempotencyUtils.java
│       └── constant/    KafkaTopicConstant.java（16 topic，含 iot-dlq）
│
├── energy-access/                              # 接入适配服务（8111）
│   └── src/main/java/com/sanduo/energy/access/
│       ├── AccessApplication.java
│       ├── config/     AccessProperties.java、AccessConsumerConfig.java
│       ├── mqtt/       AccessKafkaProducer.java
│       ├── util/       AccessKeys.java
│       ├── model/      ThingModel + Property/Service/Event/Param + EnumValue + Parser + Cache + ModelValidator
│       ├── mapper/     ThingModelMapper、DeviceMapper、DeviceStatusMapper、OnlineRecordMapper
│       ├── device/     DeviceInfo.java、DeviceInfoCache.java
│       ├── publish/    EventPublisher.java
│       ├── processor/  UplinkProcessor.java、CommandDownConsumer.java、LifecycleConsumer.java
│       └── lifecycle/  LifecycleProcessor.java、OfflineCommandRedeliverer.java
│
└── energy-tsdb/                                # 时序摄取服务（8112）
    └── src/main/java/com/sanduo/energy/tsdb/
        ├── TsdbApplication.java                # 排除 DataSource/MyBatis 自动配置，无 MySQL
        ├── config/     TsdbProperties.java、TsdbConsumerConfig.java
        ├── kafka/      TsdbKafkaProducer.java
        ├── sql/        TdengineSqlBuilder.java、TsdbBatchBuffer.java、TsdbFlushScheduler.java
        ├── writer/     TsdbWriter.java、TdengineWriter.java
        └── consumer/   PropertyTsdbConsumer.java、EventTsdbConsumer.java
```

---

## 4. 核心代码设计

### 4.1 上行处理 UplinkProcessor（接入适配的核心）

```
handle(record) →
  RouterEnvelope.decode → skip 非 PUBLISH → parseUpTopic（4 段 {pk}/{dn}/up/{type}，拒绝 down/*）
  → deviceInfoCache.get(deviceKey)（Redis + MySQL 兜底）→ 设备不存在 → traceRaw(rejectReason=DEVICE_NOT_FOUND)
  → 提取 messageId（payload.messageId 或雪花）→ messageDedup.tryOnce("access", deviceId, messageId)
  → traceRaw（原始报文留痕 iot-raw）→ 按 upType 分流：
       PROPERTY → 查物模型 → ModelValidator.validateProperties（未知拒/enum 越界拒/required 告警）
                   → 合法才发 iot-thing-property；整体非法 → traceRaw(rejectReason)
       EVENT    → ModelValidator.checkEvent 白名单 → severity 映射 → iot-thing-event
       ACK      → 解析指令 ACK → iot-command-ack（key=commandId）
       LIFECYCLE→ 转换 LifecycleMessage → iot-device-lifecycle
  （各分支 catch Exception 记日志不阻塞，毒丸防护）
```

### 4.2 消费引擎 KafkaConsumerEngine（common）

- N 个线程各持**相同 group 的独立 consumer**：分区由组自动分配，单分区仅被一个线程消费 ⇒ **单设备严格有序**，跨分区天然并行；
- `enable.auto.commit=false`：poll 批次全量处理完 `commitSync`（at-least-once：失败未进 DLQ 的记录随重放重现，不丢失）；
- 记录级失败：记日志 + 按需写 iot-dlq + **照常提交**（防 poison pill 阻塞分区）；
- daemon 线程 + 重试不退出（Kafka 未就绪时服务可先启动）；`close()` 置位 + 中断 + join 有界等待。

### 4.3 幂等去重 MessageDedup（common）

```
SETNX iot:msg:dedup:{stage}:{device_id}:{message_id}  TTL 300s
返回 true=首次可处理；false=该边界已处理，幂等跳过。
stage 按消费边界隔离（access / tsdb / shadow / rule / alarm / ws），杜绝跨边界误判。
```

### 4.4 TDengine 宽表写入 SQL 构造（tsdb · TdengineSqlBuilder，纯函数）

```sql
INSERT INTO iot_tsdb_raw.dev_1100000000000000001
  USING iot_tsdb_raw.st_prop_snd_ess_pcs
  TAGS ('1100000000000000001', '10001', '1', 'snd_ess_pcs')
  (ts, msg_id, data_type, `soc`, `run_mode`)
  VALUES (1722859200000, 'm-1', 'report', 85.2, 2)
```

- `ts` 用 **epoch 毫秒字面量**（无时区歧义）；自动建子表；TAGS null 归一为 `''`（同设备不拆子表）；
- 事件落库 `dev_{deviceId}_evt`，`payload` JSON 列承载可变载荷。

### 4.5 批量缓冲 TsdbBatchBuffer（tsdb）

```
add(sql) → 队列追加 → 达阈值(1000行/2MB) → flushNow
flushNow → writer.execute(多行 JOIN "\n")；失败 → 行回滚队头 + 上抛（当前记录→DLQ）
idle   → TsdbFlushScheduler @Scheduled(1s) 兜底冲刷
```

---

## 5. 可靠性设计

### 5.1 异常与毒丸防护
- 解析失败/校验失败：日志 + 报文进 iot-raw（rejectReason 标注），标准 topic 不产出；不抛错不阻塞分区；
- 依赖不可用：MySQL 挂 → 物模型/设备查询走缓存（Cache-Aside + L1 内存），摄取不中断；Redis 挂 → 去重退化（tryOnce 失败记日志放行，靠 TDengine 天然覆盖 + 人工补数）；Kafka 未就绪 → 消费线程重试不退出；
- TDengine 写入失败 → 缓冲回滚 + 上抛 → 记录进 iot-dlq（毒丸不阻塞分区）。

### 5.2 幂等矩阵

| 消费边界 | stage | 幂等键 | 失败处置 |
| --- | --- | --- | --- |
| access 摄入 mqtt.router | access | messageId | 记录级失败不重试（上游可重放） |
| tsdb 属性摄取 | tsdb | messageId | 缓冲失败 → DLQ + 提交 |
| tsdb 事件摄取 | tsdb | messageId | 同上 |
| 生命周期消费 | — | 事件带 deviceId+ts 天然幂等（状态机更新） | 失败 → DLQ |

### 5.3 生命周期状态机（对齐 Phase 2）
- ONLINE：`status=3` + `broker_node` + `ip` + `last_online_time`；插上线记录（按月份分区）；
- OFFLINE：`status=2` + `broker_node=NULL` + `online_seconds += COALESCE(TIMESTAMPDIFF(SECOND, last_online_time, now), 0)`；插下线记录；
- 离线补发：`leftPop iot:cmd:q:{deviceId}`（FIFO）→ 复用 D7 下行路径桥接，单次上限 `offline-max-redeliver=200`。

---

## 6. 测试方案

| 模块 | 测试 | 覆盖点 |
| --- | --- | --- |
| energy-common | MqttTopicUtilTest / RouterEnvelopeTest | 上行 topic 解析（4 段、拒绝 down/*）、信封 publish/kick round-trip、损坏 base64 |
| energy-access | ThingModelParserTest（4） | 物模型 schema 解析（属性/服务/事件/enum 值）、畸形 schema 拒绝、空模型 |
| energy-access | ModelValidatorTest（10） | 类型强转（float/int/bool/enum/string/struct）、enum 越界、未知 identifier 拒绝、severity 映射、事件白名单 |
| energy-tsdb | TdengineSqlBuilderTest（8） | 宽表/事件 SQL 构造、列序=模型序、非法列跳过、字符串转义、缺 deviceId/productKey 拒绝、payload JSON 落列 |
| energy-tsdb | TsdbBatchBufferTest（5） | 阈值冲刷、失败回滚保序、空缓冲不写、失败后恢复 |

全部为纯单元测试（不依赖 Broker/Kafka/TDengine 运行环境），`mvn -pl energy-access,energy-tsdb -am test` 通过。

### 集成验证（Phase 8 统一执行）
- docker-compose 起 Kafka/TDengine/MySQL/Redis → 造数脚本灌 mqtt.router → 断言 st_prop_snd_ess_pcs 行数/内容与 iot-raw 留痕一致；
- 双端重放：手动 reset consumer offset 重放 1 万条，断言 TDengine 无重复行（幂等兜底）；
- 断电演练：tsdb 进程 kill -9 后重启，断言缓冲/DLQ 数据不丢失。

---

## 7. 下一阶段任务（Phase 6 · 业务模块）

1. **shadow 影子模块**：消费 iot-thing-property/event → 影子 reported 双写（Redis 热 + MySQL 乐观锁版本）；desired/delta 语义与命令打通；
2. **command 指令中心**：消费 iot-command-ack 收敛指令状态机（CREATED→SENT→DEVICE_RECEIVED→EXECUTING→SUCCESS/FAILED/TIMEOUT）；离线指令写入 `iot:cmd:q`（补发链路已就绪）；超时扫描 `iot_command.state/create_time`；
3. **alarm 告警模块**：消费 iot-thing-event → 规则匹配 → iot-alarm → ES/MySQL 告警记录 + WebSocket 推送；
4. **station/system 资产扩展**：电站侧控制策略（EMS）与多租户治理；
5. **设备在线态统计**：复用 iot_device_online_record 按日聚合出在线率/时长报表。

---

## 8. 本阶段验收自评（对照 Phase 1 §13）

- ✅ 摄取链路：mqtt.router → access 校验 → iot-thing-property/event → TDengine 宽表/事件落库
- ✅ 原始留痕：iot-raw（key=messageId）追踪/补数，rejectReason 标注
- ✅ 指令 ACK：iot-command-ack（key=commandId）→ 状态回写边界就绪（消费方 Phase 6）
- ✅ 生命周期：在线态刷新 + 上下线记录 + 离线指令补发（关联 iot:online 与 iot-command-down）
- ✅ Kafka 16 topic 消费组落地 + 每边界幂等（access/tsdb）+ 毒丸进 DLQ
- ✅ TDengine 宽表写入器（自动建子表、批量多 INSERT、epoch 毫秒无时区歧义）
