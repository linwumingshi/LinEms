# Energy Storage IoT Platform · MQTT Broker 架构与高可用设计（面试速学）

> 适用对象：`energy-mqtt-broker` 模块（自包含，不依赖其他业务模块，通过 Kafka topic 与上下游交互）。
> 阅读顺序建议：①整体架构 → ②高可用总览 → ③关键代码设计（协议分发 / QoS 状态机 / CONNECT / 投递路由 / 订阅匹配 / 线程模型）→ ④Redis 与高可用关系 → ⑤容量与契约。
> 所有结论均来自源码（`backend/energy-mqtt-broker/src/main/java/com/energyx/broker/**`），可对照阅读。

---

## 1. 整体架构（部署拓扑）

设备经负载均衡打到 Broker **集群**中的任一节点；节点间**无状态共享内存**，全部通过 **Redis（会话/锁/心跳/凭据/在线态）** 与 **Kafka（跨节点路由/生命周期事件）** 协同，做到"会话跟随设备、连接可任意接管"。

```mermaid
flowchart TB
    subgraph DEV["设备层: 储能 PCS 与 网关"]
        D1["设备 A<br/>clientId = pk_dn"]
        D2["设备 B"]
        D3["设备 N"]
    end

    LB["负载均衡 / LVS / K8s Service<br/>轮询或最少连接"]

    subgraph CLUSTER["MQTT Broker 集群: N 节点, 无共享内存"]
        N1["Broker Node1<br/>nodeId = broker-1<br/>:1883 / :8883"]
        N2["Broker Node2<br/>nodeId = broker-2"]
        N3["Broker NodeK"]
    end

    subgraph MID["中间件: 高可用底座"]
        REDIS[("Redis Cluster<br/>会话 / 连接锁 / 心跳 / 凭据 / 在线态")]
        KAFKA[("Kafka<br/>mqtt.uplink / mqtt.down.nodeId<br/>mqtt.broadcast / iot-device-lifecycle")]
        MYSQL[("MySQL<br/>iot_device + iot_device_credential")]
    end

    subgraph DOWN["下游消费方"]
        ACCESS["access 服务<br/>uplink 唯一消费组"]
        EMS["EMS 能量管理"]
        SHADOW["影子服务<br/>刷新在线态 / 补发离线队列"]
        CMD["command 服务<br/>平台到设备下行指令"]
    end

    D1 -->|MQTT 3.1.1 / 5.0| LB
    D2 -->|MQTT 3.1.1 / 5.0| LB
    D3 -->|MQTT 3.1.1 / 5.0| LB
    LB --> N1
    LB --> N2
    LB --> N3

    N1 -.->|读写会话 连接锁 心跳 凭据| REDIS
    N2 -.->|读写会话 连接锁 心跳 凭据| REDIS
    N3 -.->|读写会话 连接锁 心跳 凭据| REDIS
    N1 -.->|跨节点路由信封 生命周期事件| KAFKA
    N2 -.->|跨节点路由信封 生命周期事件| KAFKA
    N3 -.->|跨节点路由信封 生命周期事件| KAFKA
    N1 -.->|首次凭据联查, 带缓存兜底| MYSQL
    N2 -.->|首次凭据联查, 带缓存兜底| MYSQL
    N3 -.->|首次凭据联查, 带缓存兜底| MYSQL

    N1 -->|mqtt.uplink 上行摄取| KAFKA
    N2 -->|mqtt.uplink 上行摄取| KAFKA
    N3 -->|mqtt.uplink 上行摄取| KAFKA
    KAFKA --> ACCESS
    KAFKA --> EMS
    KAFKA --> SHADOW
    CMD -->|下行指令到 owner 节点 mqtt.down| KAFKA
    KAFKA --> N1
    KAFKA --> N2
    KAFKA --> N3
```

**设计要点**
- Broker 节点本身是**无状态**的：内存只放"热 Session"（一个连接一个 `Session`）。任何可重建的持久态（订阅 / inflight / 离线队列 / 遗嘱 / 在线标记）都在 Redis。
- 设备重连被 LB 打到**任意**节点，都能从 Redis 重建持久会话 → **"会话跟随设备"，天然支持水平扩容**。
- 跨节点投递不靠节点间直连，统一走 Kafka topic，避免网状依赖。

---

## 2. 高可用总览（如何做到高可用）

```mermaid
flowchart LR
    subgraph HA["五大高可用机制"]
        LOCK["[1] 跨节点连接锁接管<br/>mqtt:conn:deviceKey = nodeId<br/>短租约 + 在线续期 + 宕机即接管"]
        HB["[2] 节点心跳 HA<br/>mqtt:node:nodeId TTL=30s<br/>每10s刷新, 死亡即判定"]
        SESS["[3] 会话跟随设备<br/>Redis 持久会话<br/>重连任意节点重建"]
        GS["[4] 优雅停机<br/>删心跳 + 批量释放连接锁<br/>不等锁 TTL 过期"]
        ROUTE["[5] 定向路由 + 回落<br/>owner 解析到 mqtt.down.node<br/>离线 或 竞态到 Redis 离线队列 / 广播"]
    end

    DEATH["某节点宕机"] -->|心跳 key 消失| HB
    HB -->|owner 判定死亡| LOCK
    DEATH -->|连接中断| SESS
    LOCK -->|设备重连别的节点| TAKE["新节点 overwriteConnLock 接管"]
    GS -->|主动下线| LOCK
    ROUTE -->|下行目标节点死亡| HB
```

**逐项说明**

| # | 机制 | 关键实现 | 故障处理 |
|---|------|----------|----------|
| 1 | 连接锁接管 | `SessionStore.tryAcquireConnLock`（SETNX，TTL=20s）；`refreshConnLockIfOwner` 随在线续期；`overwriteConnLock` 接管 | owner 心跳消失→跳过无效 KICK 直接 overwrite；否则发 KICK 踢远端 |
| 2 | 节点心跳 | `NodeHeartbeatScheduler` 每 10s 写 `mqtt:node:{nodeId}`（TTL=30s） | access 侧解析下行 owner 时若心跳缺失 → 判定死节点 → 回落广播/离线队列 |
| 3 | 会话跟随 | `SessionStore` 持久化 subs/inflight/offline/will 到 Redis | 设备重连任意节点 → `runSessionRestore` 加载订阅、续传 inflight、补发离线队列 |
| 4 | 优雅停机 | `MqttBrokerServer.stop()` 关 acceptor→发 DISCONNECT(0x8B)→停消费→停 producer→关池；`NodeHeartbeatScheduler.stop()` 删心跳+批量释放锁 | 其他节点立即接管，不等 20s 锁 TTL |
| 5 | 定向路由 | `MessageDeliverer.routeDirectedSlow` 用 `resolveOwnerNode` 定位设备所在节点，发 `mqtt.down.{nodeId}`；owner 缺失→离线队列；无会话→广播 | 死节点不再收到定向消息，规避"无人消费的分区" |

---

## 3. 关键代码设计

### 3.1 协议报文分发（事件循环零阻塞）

`MqttChannelInboundHandler.channelRead` 是唯一入口，按 `MqttMessageType` 分发。阻塞操作（认证/锁/持久化）一律经 `brokerExecutor` 剥离，最终会话注册与 CONNACK 回投 IO 线程。

```mermaid
flowchart TD
    R["channelRead(msg)"] --> T{报文类型}
    T -->|CONNECT| C["handleConnect 到 executor.auth 到 completeConnect"]
    T -->|PUBLISH| P["handlePublish"]
    T -->|PUBACK, PUBREC, PUBREL, PUBCOMP| Q["QoS 状态机 handler"]
    T -->|SUBSCRIBE| S["handleSubscribe"]
    T -->|UNSUBSCRIBE| U["handleUnsubscribe"]
    T -->|PINGREQ| PI["handlePingReq 到 PINGRESP"]
    T -->|DISCONNECT| D["handleDisconnect 标记优雅断开"]
    T -->|其他| X["忽略 / 调试日志"]

    C --> REG["sessionRegistry.register(session)"]
    REG --> RESTORE["executor.runSessionRestore 异步恢复"]
```

### 3.2 QoS1/2 上行状态机（PUBLISH → PUBACK/PUBCOMP 在 Kafka 持久化确认后）

**可靠性契约**：QoS1/2 上行的 `PUBACK` / `PUBCOMP` 仅在 Kafka 路由**持久化回调成功**后才发送；路由失败则关连接，设备重连重传（at-least-once）。

```mermaid
flowchart TD
    START(["收到 PUBLISH(qos)"]) --> PUBRCV["PUBLISH_RCVD<br/>校验 dup / 重复 pktId"]
    PUBRCV --> ROUTE["ROUTE: deliverer.deliver 投递到路由"]
    ROUTE --> WAITK["WAIT_KAFKA<br/>Kafka sendBytes(onRouted, onRouteFailed)"]

    WAITK -->|qos1 持久化成功| PUBACK["发 PUBACK"]
    WAITK -->|qos2 持久化成功| PUBREC["发 PUBREC, 进 inboundQos2"]
    WAITK -->|路由失败| CLOSE["channel.close<br/>设备重连重传 (at-least-once)"]

    PUBACK --> FIN1(["QoS1 完成"])
    PUBREC --> WAITREL["WAIT_PUBREL<br/>缓存 InboundPublish[pktId]"]
    WAITREL -->|收到 PUBREL 才二次路由| ROUTE2["ROUTE2: 恰好一次"]
    ROUTE2 --> WAITK2["WAIT_KAFKA2<br/>Kafka 持久化确认"]
    WAITK2 --> PUBCOMP["发 PUBCOMP"]
    PUBCOMP --> FIN2(["QoS2 完成 (exactly-once)"])
```

- **QoS2 入站"恰好一次"**：`inboundQos2` 缓存，收到 `PUBREL` 才路由（保证恰好一次语义）；重复 `PUBREL` 直接回 `PUBCOMP`（幂等）。
- **QoS2 出站"恰好一次"**：见 3.3。

### 3.3 QoS1/2 下行状态机 + inflight 续传（断线重连不丢）

`Session.outboundInflight` 缓存待确认报文；持久会话同步到 Redis `mqtt:inflight`，重连后 `resendInflight` 按状态续传。

```mermaid
flowchart TD
    SEND["下行 PUBLISH(pktId, qos)"] --> APK["AWAIT_PUBACK (qos1)"]
    SEND --> APR["AWAIT_PUBREC (qos2)"]
    APK -->|收到 PUBACK| DONE["DONE: 移除 inflight"]
    APR -->|收到 PUBREC| SPR["SEND_PUBREL: 发 PUBREL"]
    SPR --> APCB["AWAIT_PUBCOMP: 等 PUBCOMP"]
    APCB -->|收到 PUBCOMP| DONE
    DONE --> FIN(["续传完成, 会话同步 Redis"])
```

断线重连续传：AWAITING_PUBACK 重发 PUBLISH(dup)；AWAITING_PUBREC 重发 PUBLISH(dup)；AWAITING_PUBCOMP 按原 pktId 重发 PUBREL。

### 3.4 CONNECT 处理流程（认证 → 连接锁 → 会话恢复）

```mermaid
flowchart TD
    DEV["设备"] --> IO["Netty IO 线程<br/>CONNECT 校验: 版本 / 空clientId / 重复CONNECT"]
    IO --> EX["brokerExecutor<br/>提交认证任务(信号量限流)"]
    EX --> AUTH{"认证结果?"}
    AUTH -->|失败| AF["incrAuthFail, 达阈值封禁"]
    AF --> ACLOSE["sendConnAck(拒绝) + close"]
    AUTH -->|通过| LOCK{"tryAcquireConnLock<br/>被远端占用?"}
    LOCK -->|是| ALIVE{"isNodeAlive(owner)?"}
    ALIVE -->|存活| KICK["KICK 广播踢远端"]
    ALIVE -->|死亡| OW["直接 overwrite 接管"]
    KICK --> OW
    LOCK -->|否, 抢到| OW
    OW --> COMP["completeConnect<br/>注册会话 / 设 idle / 发 CONNACK"]
    COMP --> REST["runSessionRestore 异步<br/>加载 subs / inflight / offline / will"]
    REST --> ONL["notifyOnline 生命周期事件(Kafka)"]
```

### 3.5 消息投递路径（本地 + 跨节点定向路由）

```mermaid
flowchart TD
    IN["设备 PUBLISH(ACL通过)"] --> DEL["deliver(topic, payload, qos)"]
    DEL --> RET["retain? 到 RetainedMessageStore 新者胜"]
    DEL --> LOCAL["deliverLocal() 本节点订阅者"]
    DEL --> XN{"跨节点路由?<br/>sourceNode == null"}

    XN -->|上行 up 开头| UP["mqtt.uplink<br/>key = deviceKey 单设备有序"]
    XN -->|下行 / 遗嘱| OWN{"resolveOwnerNode?<br/>mqtt:conn 定位"}
    OWN -->|本节点| LOCAL
    OWN -->|跨节点| DOWN["mqtt.down.ownerNode"]
    OWN -->|owner缺失 且有会话| OFFQ["Redis 离线队列"]
    OWN -->|无会话 或 竞态| BC["mqtt.broadcast 广播兜底"]

    LOCAL --> MATCH["LocalSubscriberIndex.match(trie)"]
    MATCH --> TO["deliverToSession 到 背压 / inflight"]
    DOWN -->|RouterConsumer消费| TO
    BC -->|RouterConsumer 每节点唯一组| TO

    TO --> QOS{"qos"}
    QOS -->|0| W0["writeAndFlush"]
    QOS -->|1 或 2| W12["allocPacketId 到 inflight 到持久化 到 write"]
```

**路由通道（阶段 2 定向模型）**
- `mqtt.uplink`：设备上行，key=deviceKey（单设备有序），仅 access 唯一消费组摄取。
- `mqtt.down.{nodeId}`：平台/跨节点下行，按连接锁 owner 定向，仅目标节点消费。
- `mqtt.broadcast`：KICK 踢线 / owner 缺失回落，每节点唯一消费组全量 fan-out + sourceNode 去重。
- `mqtt.router`（兼容期）：仅 `router-legacy-broadcast=true` 启用，全量升级后关闭删除。

### 3.6 订阅匹配（topic trie，O(层级数)）

`LocalSubscriberIndex` 用分层 trie 替代 O(N) 线性扫描，支撑 50 万级订阅：
- `+` 单层级通配；`#` 锚定在所在层级（`hashBindings`）；共享订阅 `$share/{group}/{filter}` 组内轮询选一（QoS 取组内最大）。

```mermaid
flowchart LR
    SUB["add(filter, qos)"] --> SPLIT["按 '/' 分层"]
    SPLIT --> HASH{"# 在末层?"}
    HASH -->|是| HB["hashBindings(deviceKey)"]
    HASH -->|否| EB["exactBindings(deviceKey)"]
    MATCH["match(topic)"] --> COL["collect 递归(读锁)"]
    COL --> DED["dedupeShared(共享组轮询 + 最高QoS)"]
```

### 3.7 线程模型（性能与稳定基石）

```mermaid
flowchart TB
    subgraph IO["Netty EventLoop, 每连接绑定一个"]
        E1["编解码 + 路由分发(纯内存)"]
        E2["channel 写出收敛到本 EventLoop<br/>保证同连接写序"]
    end
    subgraph BIZ["brokerExecutor, 业务线程池, 队列1万, 拒绝=记日志丢弃"]
        B1["认证 Redis / MySQL"]
        B2["会话持久化 / 续期"]
        B3["Kafka 生命周期生产"]
        B4["离线投递 / 遗嘱"]
    end
    subgraph RC["RouterConsumer, 独立 Kafka 消费线程"]
        R1["down / broadcast / legacy 三引擎"]
    end
    subgraph SCH["brokerScheduler, 单线程延迟"]
        S1["Will Delay 延迟投递"]
    end

    IO -->|慢路径| BIZ
    BIZ -->|回投| IO
    RC -->|deliverToSession| IO
    KAFKA[(Kafka)] --> RC
```

**铁律**：Netty EventLoop 上禁止任何 Redis/MySQL/Kafka 同步阻塞调用；所有慢路径走 `brokerExecutor`。拒绝策略是"记日志丢弃"而非 `CallerRunsPolicy`（后者会让 IO 线程内联执行阻塞任务，卡死整个 EventLoop）。

---

## 4. Redis 与高可用关系

```mermaid
flowchart LR
    subgraph KEYS["BrokerKeys, 唯一 key 出口, 见 docs/design/Redis-key规范.md"]
        K1["mqtt:conn:deviceKey<br/>连接锁(节点接管)"]
        K2["mqtt:node:nodeId<br/>心跳(死亡判定)"]
        K3["mqtt:session / mqtt:subs<br/>mqtt:inflight / mqtt:offline<br/>会话跟随"]
        K4["mqtt:retained:topic<br/>保留消息(跨节点权威)"]
        K5["cache:cred:clientKey<br/>凭据缓存(30min)"]
        K6["iot:online:deviceId<br/>在线标记(心跳续期)"]
        K7["mqtt:nonce / mqtt:authfail / mqtt:ban<br/>防重放 / 封禁(跨节点)"]
    end
    HA1[连接锁接管] --> K1
    HA2[节点心跳HA] --> K2
    HA3[会话跟随] --> K3
    HA4[保留消息] --> K4
    HA5[认证] --> K5
    HA5[认证] --> K6
    HA5[认证] --> K7
```

**Lua 原子脚本**（`SessionStore`）保证并发安全：`PUSH_OFFLINE`（RPUSH+LTRIM+EXPIRE 单 RTT）、`POP_OFFLINE`（LRANGE+DEL 避免新消息误删）、`RELEASE_LOCK`（GET==owner 才 DEL）、`REFRESH_LOCK`、`INCR_AUTH_FAIL`、`SET_RETAINED_IF_NEWER`（新者胜，防时钟漂移覆盖）。

---

## 5. 容量目标与安全契约（面试常问）

**容量目标**（Phase 1）：单节点 25 万连接 / 5 万 msg/s；集群 100 万连接；跨节点路由 P99 ≤ 10ms。

**认证契约**（设备 SDK 必须遵循）：
```
clientId  = {productKey}_{deviceName}
username  = {clientId}&{timestamp}&{nonce}
password  = hex( HMAC-SHA256(deviceSecret, username) )
```
- `timestamp` 毫秒，±2min 窗口；`nonce` 经 Redis SETNX 一次性消费（5min TTL）防重放。
- 连续失败 ≥10 次 → `mqtt:ban:{clientId}` 封禁 300s（跨节点共享，自然过期解封）。
- mTLS（P1-12）：客户端证书 CN 必须等于 clientId。
- Topic ACL：`canPublish` 仅限本设备 `up/{type}`、`ota/{type}`；`canSubscribe` 仅限本设备 `down/*`、`ota/down`（支持 `$share` 前缀）。

**优雅停机顺序**：关 acceptor → MQTT5 发 DISCONNECT(0x8B) → 停 RouterConsumer → 停 KafkaProducer(flush) → 关业务线程池；`NodeHeartbeatScheduler.stop()` 删心跳 + 批量释放本节点连接锁，让其他节点立即接管。

---

## 6. 三十秒速记（面试电梯陈述）

> "我们的 MQTT Broker 是**无状态节点 + Redis/Kafka 状态外置**的集群架构。高可用靠四件事：①**跨节点连接锁**（Redis SETNX 短租约，宕机即接管，避免同 clientId 双连接）；②**节点心跳**（30s TTL，死节点被判定后下行回落广播/离线队列）；③**会话跟随设备**（订阅/inflight/离线队列全在 Redis，重连任意节点重建）；④**优雅停机**（删心跳+批量释放锁，不等 TTL）。可靠性靠 QoS 状态机：QoS1/2 的 PUBACK/PUBCOMP **必须等 Kafka 持久化确认**才回，失败关连接让设备重传（at-least-once）；下行 inflight 持久化支持断线续传。性能靠**线程模型铁律**——EventLoop 零阻塞，所有慢路径剥离到业务线程池，拒绝策略丢弃而非内联执行。"
