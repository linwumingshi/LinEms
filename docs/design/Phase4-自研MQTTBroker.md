# Phase 4 · 设备接入模块（自研 Netty MQTT Broker）— 设计说明

> 版本：v1.0 ｜ 日期：2026-08-06 ｜ 阶段：设备接入
> 上游依赖：Phase 1 §4（Broker 架构/认证/路由/HA）、Phase 2（Redis key 规范、iot_device/iot_device_credential）、Phase 3（工程骨架、MqttTopicUtil、KafkaTopicConstant）
> 验收对照：Phase 1 §13（百万连接 / 跨节点路由 / QoS 状态机 / keepalive / 优雅停机 / 认证与防伪装）

---

## 1. 设计说明

本阶段交付**自研 Netty MQTT Broker** 集群节点，承担「设备连接接入 + 消息路由 + 生命周期」三大职责。它是整个平台的**设备面入口**：一切设备流量先到 Broker，再由 Phase 5 消息处理模块从 Kafka 摄取落库。

### 1.1 交付范围

| 交付物 | 说明 |
| --- | --- |
| energy-mqtt-broker 模块 | Netty 服务端 + 认证 + 会话 + 路由 + QoS + 生命周期，约 30 个类 |
| 协议 | MQTT 3.1.1 / 5.0（netty-codec-mqtt），QoS 0/1/2 完整状态机 |
| 认证 | HMAC-SHA256 签名 + nonce 防重放 + 时间窗 + ACL 防伪装 + 失败封禁 |
| 会话 | 内存热 Session + Redis 持久态（订阅/inflight/离线队列），故障接管 |
| 跨节点路由 | Kafka mqtt.router，每节点唯一消费组全量 fan-out + sourceNode 去重 |
| 生命周期 | Redis iot:online + Kafka iot-device-lifecycle 双通道 |
| 运维 | /internal/broker/stats 指标端点 + 优雅停机（MQTT5 DISCONNECT 0x8B） |
| 测试 | 12 个纯单元测试（匹配/签名/ACL/packetId 分配） |

### 1.2 职责边界（关键）

- **Broker 只做「连接 + 消息路由」**：不解析物模型、不落业务库、不触发告警。
- 设备上行报文（property/event）由 Broker 按 ACL 校验后**完整原样**路由到 Kafka（Phase 5 摄取），**Broker 不缓存、不转换业务载荷**。
- 平台下行指令（Phase 6 command 模块）经 `iot-command-down` → access adapter → Broker `down/command` topic 下发，Broker 只负责按订阅/QoS 投递。

### 1.3 容量目标对应

| 指标 | 目标 | 本阶段落地 |
| --- | --- | --- |
| 单节点连接 | 25 万 | maxConnections 准入 + 内存 Session（≈2KB/连接） |
| 单节点吞吐 | 5 万 msg/s | 纯内存路由 + 批量异步 Redis/Kafka |
| 跨节点路由延迟 | P99 ≤ 10ms | mqtt.router 24 分区 + 单线程消费逐条投递 |
| 集群规模 | 100 万连接 | L4 LB 粘性 + conn 锁跨节点踢线 + 会话故障接管 |

---

## 2. 技术决策（含理由）

### D1. 自研 Broker 而非 EMQX/Mosquitto
理由（承接 Phase 1 ADR）：① 设备身份与平台账号体系强耦合，认证钩子必须深度定制（HMAC + Redis nonce + ACL 白名单），开源 Broker 的 auth callback 无法表达「设备只可 publish 到 `{pk}/{dn}/up/*`」的粒度；② 跨节点路由（mqtt.router）需要与 Kafka 消息总线打通，自研可在投递路径上直接落 Kafka，避免二次转发；③ 面试作品需要展示协议栈实现能力，这是核心卖点。自研成本可控：netty-codec-mqtt 已提供标准编解码，业务重点是认证/会话/路由三个模块。

### D2. 采用 netty-codec-mqtt 标准 codec，不手写编解码
理由：MQTT 3.1.1/5.0 报文格式细节（变长剩余长度、v5 properties）容易出错，标准 codec 稳定且经过广泛验证；手写剩余长度编码收益极低。自研价值体现在**会话/路由/认证**，而非字节级编解码。

### D3. 原生 kafka-clients 替代 spring-kafka（承接 Phase 3 D9）
理由：镜像缺失 `spring-boot-starter-kafka`（硬约束）。但即便镜像可用，Broker 路由场景也更适合原生客户端：无 Spring 包装、线程模型透明、可精确控制分区策略与批量参数。生产者开启 `idempotence + acks=all` 保证跨节点投递一致性；消费者每节点唯一 group 实现全量 fan-out。

### D4. 内存热 Session + Redis 持久态，不迁移 Session
- Session（连接态）只存在设备所在节点，绝不跨节点复制/迁移；
- Redis 只存**可重建的持久态**：订阅（mqtt:subs）、QoS inflight（mqtt:inflight）、离线队列（mqtt:offline）、会话元数据（mqtt:session）；
- 故障接管：节点宕机 → L4 把设备重连到其他节点 → 该节点按 clientId 从 Redis 重建持久会话。
理由：百万连接若全量镜像到 Redis 会放大 2~3 倍 Redis 带宽且引入一致性复杂度；「连接跟设备走、状态存 Redis」是业界（EMQX/HiveMQ）通用模式。

### D5. 认证：HMAC-SHA256 + nonce + 时间窗 + ACL + 封禁
- `clientId = {productKey}_{deviceName}`（MqttTopicUtil 锁定）；`username = clientId&timestamp&nonce`；`password = hex(HMAC-SHA256(deviceSecret, username))`；
- timestamp ∈ [now-2min, now+2min] 防时间戳重放；nonce 经 Redis `SETNX mqtt:nonce:{nonce}`（5min TTL）一次性消费，防重放；
- ACL：设备只可 publish 到 `{pk}/{dn}/up/{type∈property|event|lifecycle|ack}`，只可 subscribe 到 `{pk}/{dn}/down/*`，其余一律拒绝并断开——**防设备伪装**（一台被攻破的 PCS 不能冒充同电站其他设备上报）；
- 凭据链路：`cache:cred:{deviceKey}`（30min）→ 未命中查 MySQL iot_device + iot_device_credential → 回写缓存；MySQL 不可用时缓存兜底，Broker 不因此下线；
- 连续失败 ≥ N 次短期封禁（本节点内存计数，跨节点封禁列为 Phase 6 增强）。
理由：密码不落网（只传签名）、nonce 防重放、ACL 防横向越权，三层都是储能安全红线。

### D6. 跨节点路由：每节点唯一消费组 + sourceNode 去重
- mqtt.router（24 分区，key=topic）：设备上行按 topic hash 落到分区，任意节点消费；
- **每节点一个唯一消费组** `mqtt-router-{nodeId}` ⇒ 每个分区被所有节点都消费到（全量 fan-out）；
- 消费者按 `envelope.sourceNode` 丢弃本节点自己发的消息，保证「一次投递」；
- KICK 信封用于同 clientId 跨节点踢线（conn 锁被远端占用时）。
理由：fan-out 模型下任何节点都能订阅到任何设备的 topic，无需主题分区与设备归属的强绑定，Broker 无状态化，扩容即加节点。

### D7. QoS 状态机 + 离线队列 + 保留消息
- outbound：QoS1 `PUBLISH→PUBACK`；QoS2 `PUBLISH→PUBREC→PUBREL→PUBCOMP`，inflight 同步 Redis，断线重连续传（QoS1 重发 dup PUBLISH / QoS2 按状态续传）；
- inbound：QoS2 收到 PUBREL 才路由（恰好一次）；设备上行以 QoS1 为主（Phase 1 §6.1）；
- 持久会话（cleanSession=false）离线 → 幽灵订阅保留在索引 → 命中发布进离线队列（容量上限 500，TTL 7d），上线补发；
- 保留消息存 `mqtt:retained:{topic}`（跨节点权威）+ 节点内存缓存（本节点读），新订阅投递匹配的保留消息。
理由：储能控制类指令必须 QoS1+ 保序（ADR-009），离线补发保证设备重启不丢下发的目标值。

### D8. 线程模型：IO 线程零阻塞
Netty EventLoop 只做「编解码 + 内存路由 + 写回」；认证（Redis/MySQL）、会话恢复、inflight 持久化、在线续期等阻塞操作全部经 `brokerExecutor`（独立线程池）剥离；Router 消费独立专用线程。跨线程写回统一走 `channel.eventLoop().execute()`，保证同连接写序（QoS ACK 顺序依赖）。
理由：IO 线程阻塞是 Netty 高吞吐的头号杀手，5 万 msg/s 下任何同步 Redis 调用都会把事件循环卡死。

### D9. 优雅停机：MQTT5 DISCONNECT 0x8B + 持久态先行
顺序：关 accept → 对 v5 客户端发 DISCONNECT(0x8B，服务器关闭) → 关闭连接（触发持久化/离线事件）→ 停 Router 消费 → flush 生产者 → 关线程池。持久会话状态已在 Redis，设备重连其他节点即接管。
理由：停机不丢会话、不误报离线、不给客户端留下「连接被重置」的模糊感知。

### D10. 动态 IdleStateHandler（keepalive 1.5×）
Pipeline 预置 90s 兜底 idle；CONNECT 携带 keepalive 后按 `ceil(keepalive×1.5)` 重建 IdleStateHandler。心跳超时 → 触发 channelInactive → 离线事件（reason=HEARTBEAT_TIMEOUT）。
理由：1.5× 缓冲网络抖动，是 MQTT 规范推荐值；动态重建避免统一阈值误杀长 keepalive 设备。

### D11. 技术债/Phase 8 优化项（如实记录）
- 订阅匹配为 O(N) 线性扫描 → 换 trie（Phase 1 §4.6 既定）；
- 认证失败计数为本节点内存 → 跨节点统一计数需 Redis INCR+EXPIRE；
- v5 遗嘱延迟（will delay >0）未实现，按 0 立即投递；
- 幽灵订阅无主动清理（依赖 mqtt:session 7d TTL）→ 僵尸设备索引清理需定时扫描。

---

## 3. 项目目录结构

```text
backend/energy-mqtt-broker/
├── pom.xml                                # netty-codec-mqtt/handler/transport + kafka-clients 3.6.2 + common
└── src/
    ├── main/java/com/sanduo/energy/broker/
    │   ├── MqttBrokerApplication.java     # @MapperScan(broker.mapper) + BrokerProperties
    │   ├── config/
    │   │   ├── BrokerProperties.java      # sanduo.broker.*（端口/容量/阈值/ttl/kafka）
    │   │   ├── BrokerExecutorConfig.java  # 业务线程池（IO 线程零阻塞）
    │   │   └── NettyServerConfig.java     # NIO ServerBootstrap + Pipeline（decoder/encoder/idle/handler）
    │   ├── server/
    │   │   └── MqttBrokerServer.java      # start/stop 优雅停机（DISCONNECT 0x8B）
    │   ├── handler/
    │   │   └── MqttChannelInboundHandler.java  # @Sharable 报文分发（CONNECT..DISCONNECT 全矩阵）
    │   ├── auth/
    │   │   ├── HmacSigner.java            # 纯函数 HMAC-SHA256 + 常数时间比较
    │   │   ├── DeviceCredential.java      # 凭据聚合模型（缓存 JSON）
    │   │   ├── AuthResult.java            # 认证结果 + CONNACK 码
    │   │   ├── DeviceRow.java / CredentialRow.java  # mapper 投影
    │   │   ├── TopicAcl.java              # 上下行 ACL（防伪装）
    │   │   └── DeviceAuthService.java     # 认证编排（HMAC+nonce+时间窗+状态+封禁）
    │   ├── mapper/
    │   │   ├── DeviceMapper.java          # @Select 只读投影
    │   │   └── DeviceCredentialMapper.java
    │   ├── session/
    │   │   ├── Session.java               # 内存热会话（subscriptions/inflight/inboundQos2/attrs）
    │   │   ├── SessionRegistry.java       # 节点内注册表 + 准入计数 + 停机全量下线
    │   │   ├── SessionStore.java          # Redis mqtt:* 持久化（session/subs/inflight/offline/conn/nonce）
    │   │   ├── MqttSubscription.java / InflightMessage.java / InboundPublish.java / OfflineMessage.java
    │   ├── routing/
    │   │   ├── LocalSubscriberIndex.java  # topicFilter→deviceKey→binding 订阅索引
    │   │   ├── RouterEnvelope.java        # 跨节点信封（PUBLISH/KICK）
    │   │   ├── MessageDeliverer.java      # 本地投递 + 路由 + QoS 状态机 + 保留/离线
    │   │   ├── RouterConsumer.java        # 每节点唯一 group fan-out + sourceNode 去重
    │   │   └── KafkaTopicInitializer.java # AdminClient 预建 topic（24 分区）
    │   ├── mqtt/KafkaEventProducer.java   # 原生生产者（idempotent）单实例
    │   ├── lifecycle/LifecycleNotifier.java  # iot:online + iot-device-lifecycle 双通道
    │   ├── retained/RetainedMessageStore.java # Redis 权威 + 内存缓存
    │   ├── stats/BrokerStats.java + BrokerStatsController.java  # 指标 + /internal/broker/stats
    │   └── util/
    │       ├── BrokerKeys.java            # Redis key 唯一出口（对齐规范文档）
    │       └── TopicMatcher.java          # MQTT 通配匹配（+/#/$share）
    └── test/java/.../
        ├── util/TopicMatcherTest.java     # 7 用例
        ├── auth/HmacSignerTest.java       # 4 用例
        ├── auth/TopicAclTest.java         # 4 用例
        └── session/SessionTest.java       # 4 用例（packetId 分配器/订阅编解码）
```

### 端口与注册

| 项 | 值 |
| --- | --- |
| MQTT 端口 | 1883（`BROKER_MQTT_PORT`） |
| 管理端口 | 8082（/internal/broker/stats、/actuator） |
| Nacos 服务名 | energy-mqtt-broker（group=ENERGY），多节点同名注册 |
| 消费组 | mqtt-router-{nodeId}（每节点唯一） |

---

## 4. 核心代码要点

### 4.1 认证签名契约（设备 SDK 必须遵循）
```java
clientId = buildClientId(pk, dn)                    // {productKey}_{deviceName}
username = clientId + "&" + timestamp + "&" + nonce // timestamp 毫秒，nonce 随机串
password = HmacSigner.sign(deviceSecret, clientId, timestamp, nonce)  // 64 位小写 hex
```
验证顺序：clientId/username 解析 → 封禁检查 → 时间窗(±2min) → nonce SETNX → 凭据加载+状态 → 签名常数时间比较。

### 4.2 跨节点一次投递
```java
// 本节点设备 PUBLISH（sourceNode=null）
deliverer.deliver(topic, payload, qos, retain, null);
//   ├─▶ deliverLocal()：订阅索引匹配 → 在线直发 / 持久离线入队
//   └─▶ kafka send(mqtt.router, key=topic, envelope{sourceNode=thisNode, payloadBase64, qos})
// 远端 RouterConsumer：sourceNode != thisNode → deliver(topic,..,sourceNode) → deliverLocal() 不回发
```

### 4.3 QoS1 outbound 状态机
```java
sendPublish: allocPacketId → inflight.put(id, AWAITING_PUBACK) → Redis 异步持久化 → write
handlePubAck: inflight.remove(id) → Redis 删除           // 完成
// 断线重连：loadInflight → 状态 AWAITING_PUBACK/PUBREC 重发 dup PUBLISH，AWAITING_PUBCOMP 重发 PUBREL
```

### 4.4 keepalive 与在线判定
```java
replaceIdleHandler(channel, keepalive)   // idle = ceil(keepalive × 1.5)
PINGREQ/上报 → touch() + shouldRenewOnline()（10s 节流）→ 续期 iot:online TTL
IdleStateEvent / 异常关闭 → channelInactive → notifyOffline(reason=HEARTBEAT_TIMEOUT)
```

### 4.5 运维指标
```text
GET :8082/internal/broker/stats
→ {nodeId, connections, subscriptions, messagesIn, messagesOut,
   messagesRoutedCrossNode, authFailures, acceptedConnections, rejectedConnections, uptimeMillis}
```

---

## 5. 测试方案

| 类型 | 用例 | 工具 |
| --- | --- | --- |
| 单元 | TopicMatcher：精确/+/#/末尾#/$共享订阅/$SYS 保留 | JUnit 5，7 用例 |
| 单元 | HmacSigner：确定性/任一输入变化签名变化/null 安全/常数时间 | JUnit 5，4 用例 |
| 单元 | TopicAcl：本设备上下行放行/越权发布订阅拒绝/白名单外 type 拒绝 | JUnit 5，4 用例 |
| 单元 | Session：packetId 1~65535 循环/跳过占用/满返回 -1/订阅编解码 | JUnit 5，4 用例 |
| 集成（冒烟） | 本地启动 Broker + MQTTX/mosquitto 客户端连 1883 验证 CONNECT/SUB/PUB | 手工 |
| 集成（认证） | 用 HmacSigner 生成签名，正向/错误密码/重放 nonce/超窗 timestamp 各验一次 | 手工 + Phase 8 自动化 |
| 压测（Phase 8） | 100 万连接仿真 + 5 万 msg/s 上行 + 跨节点路由延迟 P99 | 自研模拟器 |

> 说明：本阶段仅保留**纯单元测试**（12 个，不依赖 Spring 容器/基础设施）；认证与路由的连通性冒烟依赖 Redis/MySQL/Kafka 在线，放本地环境手工验证（见 §7）。

---

## 6. 下一阶段任务（Phase 5 · 消息处理模块）

1. access adapter：消费 mqtt.router / 直连设备上行，实现报文解析（物模型校验）与标准化；
2. 摄取链路：`iot-thing-property`（key=deviceId 保序）→ TDengine 落库；`iot-thing-event` → 告警/ES；
3. iot-raw 原始报文留痕（追踪/补数）；设备 ACK（up/ack）→ iot-command-ack 回写指令状态；
4. 生命周期事件消费：刷新设备在线态/上下线记录、触发离线命令补发（关联 iot:online 与 iot-command-down）；
5. Kafka 15 topic 的完整消费组与幂等设计落代码；TDengine 宽表写入器。

---

## 7. 验证记录（本阶段）

- [x] `mvn -pl energy-mqtt-broker -am compile` **BUILD SUCCESS**（Netty codec + kafka-clients + common 全部解析）
- [x] 单元测试 **12 通过 / 0 失败**（broker 模块），全工程累计 **19 通过 / 0 失败**
- [x] Redis-key规范.md 同步：inflight 细化 Hash（含理由）、新增 mqtt:retained 登记
- [ ] Broker 本地启动冒烟：1883 监听 / CONNECT 认证 / SUB/PUB 往返 / 优雅停机（依赖 Redis/MySQL/Kafka/Nacos 容器就绪）
- [ ] 双节点跨节点路由验证：broker-1 上行 → broker-2 订阅接收，mqtt.router 消费组生效
