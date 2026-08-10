# energy-mqtt-broker 生产级评估报告

> 评估对象：`backend/energy-mqtt-broker`（自研 Netty MQTT Broker）  
> 评估视角：云端 IoT 设备接入平台 MQTT Broker 标准（对标 EMQX / HiveMQ / VerneMQ / 阿里云 IoT）  
> 评估日期：2026-08-09 · 基于代码实际实现（类/方法级）  
> **更新日期：2026-08-10 · 阶段 1 + 阶段 2 修复落地后，新增「七、修复进展对照」（原评估记录保留，作为基线）**

---

## 一、当前 mqttbroker 架构分析

### 1.1 架构总览

```
设备(18831/8883) ──▶ Netty (TransportFactory: Epoll/KQueue/NIO 自适应) ServerBootstrap
                        │  boss=1, worker=CPU×2
                        ▼ MqttChannelInboundHandler (@Sharable 单例)
              ┌─────────┼──────────────────┐
              ▼         ▼                  ▼
        DeviceAuth   LocalSubscriber    SessionStore
        (Redis缓存    Index(trie订阅树   (Redis: session/subs/
         +MySQL兜底)   O(depth)匹配)      inflight/offline/connLock)
              │         │
              ▼         ▼
        MessageDeliverer ──▶ Kafka（阶段2 定向路由，替代全量 fan-out）
                                  │
        ┌─────────────────────────┼─────────────────────────┐
        ▼                         ▼                         ▼
  mqtt.uplink(设备上行)    mqtt.down.{nodeId}       mqtt.broadcast(KICK/回落)
  仅 access 唯一消费组     仅目标节点消费             每节点唯一消费组
        │                         │
        ▼                         ▼
  energy-access(业务摄取)    RouterConsumer(本节点)
                              → deliverToSession → 设备
```

- **模块划分**：`handler`（协议分发）/ `auth`（HMAC 认证+ACL）/ `session`（内存 Session + Redis 持久态）/ `routing`（本地投递 + Kafka 定向路由）/ `retained` / `lifecycle` / `stats`。包职责清晰，无业务逻辑渗入，Broker 与业务通过 Kafka 二进制信封（`RouterEnvelopeCodec`）解耦 —— **职责边界设计合理，阶段 2 后保持**。
- **线程模型设计**：IO 线程只做编解码+内存分发，慢路径（认证/Redis/Kafka 生命周期）剥离到 `brokerExecutor`；路由消费走独立多线程引擎（`BytesKafkaConsumerEngine`，分区并行 + 手动提交）。阶段 1 已清零 EventLoop 阻塞调用，**模型与实现一致**。
- **集群方案（阶段 2 升级）**：Redis 连接锁（`mqtt:conn:{deviceKey}`）承担「踢线 + 下行路由寻址」双职责；会话状态（订阅/inflight/离线队列）存 Redis，节点宕机后设备重连任意节点可恢复 —— "会话跟随设备"。下行按 owner 定向投递 `mqtt.down.{nodeId}`，离线回落 Redis 离线队列 / `mqtt.broadcast` 兜底。
- **上行链路（阶段 2 升级）**：设备 PUBLISH → 本地投递 + 写 `mqtt.uplink`（key=deviceKey，24 分区）→ energy-access 唯一消费组摄取。Broker 不再全量 fan-out，Kafka 出口流量从 (N+1)×总量 降为 ≈1×总量。

### 1.2 结论先行（2026-08-10 更新）

原始评估识别的 **5 个 P0 全部修复**，**P1 中 9/12 项已修复或部分修复**（详见第七章对照表），架构级改造（fan-out → 定向路由、O(N) → trie、NIO → Epoll 自适应、JSON 信封 → 二进制）已在阶段 2 落地。当前状态：**单节点可生产、十万设备架构就绪**——70 个单测全绿，真实环境端到端验证通过（上行 QoS1 PUBACK 49ms、下行定向信封到达设备、持久会话恢复、ACL 拒绝、指标计数正确）。

剩余差距集中在：**速率限制/配额、压测与 v3/v5 互操作矩阵、真机 mTLS 联测、OTel 全链路追踪**——对应阶段 3 的集群验证与压测资产。

---

## 二、已有生产能力（值得保留的设计）

| 能力                                                      | 实现位置                                                                | 评价                   |
| ------------------------------------------------------- | ------------------------------------------------------------------- | -------------------- |
| 认证异步化                                                   | `MqttChannelInboundHandler.handleConnect` → executor → eventLoop 回投 | 慢路径剥离方向正确            |
| HMAC-SHA256 + nonce 防重放 + ±2min 时间窗 + 常数时间比较            | `DeviceAuthService` / `HmacSigner` / `SessionStore.consumeNonce`    | 与阿里云一机一密同级           |
| 设备/凭据状态机校验（禁用/封禁/未激活/吊销/过期）                             | `DeviceAuthService.checkStatus`                                     | 完整                   |
| 凭据三级缓存（Redis 30min → MySQL 兜底 → 回写）                     | `DeviceAuthService.loadCredential`                                  | MySQL 挂不影响接入，正确      |
| Topic ACL 白名单（up/{property,event,lifecycle,ack}，down/*） | `TopicAcl`                                                          | 防跨设备伪装，白名单制正确        |
| 同 clientId 踢线（本节点直接踢 + 跨节点连接锁 + KICK 信封）                | `completeConnect` / `kickRemote` / `RouterConsumer`                 | 闭环完整                 |
| 入站 QoS2 "收 PUBREL 才路由"                                  | `handlePublish` case2 / `handlePubRel`                              | 恰好一次语义正确             |
| 出站 QoS1/2 状态机 + Redis in-flight 续传                      | `MessageDeliverer` / `handlePubRec/PubComp`                         | 骨架正确（细节有 bug，见 P1-8） |
| 持久会话（sessionPresent/订阅恢复/离线队列/inflight 续传）              | `completeConnect` 异步恢复段                                             | 语义正确                 |
| 保留消息（Redis 权威 + 内存读缓存）                                  | `RetainedMessageStore`                                              | 方向正确（冷启动有缺陷）         |
| KeepAlive 1.5× 动态替换 IdleStateHandler                    | `replaceIdleHandler`                                                | 符合 RFC               |
| 优雅停机（停 accept → 通知设备 → 停消费 → flush producer）            | `MqttBrokerServer.stop`                                             | 步骤完整（v5 报文有 bug）     |
| 连接准入控制（maxConnections TCP 层计数）                          | `channelActive`                                                     | 正确，含未认证连接            |
| Kafka 幂等生产者 acks=all + 无限重试                             | `KafkaEventProducer`                                                | 路由不重复不乱序             |
| TLS 8883 可选双监听、证书缺失 fail-fast                           | `NettyServerConfig`                                                 | 骨架正确                 |
| 拒绝策略不用 CallerRunsPolicy 的取舍论证                           | `BrokerExecutorConfig` 注释                                           | 判断专业                 |

---

## 三、存在的问题（按严重程度排序）

### P0 —— 阻断生产上线

**P0-1 离线队列补发逻辑错误，持久会话离线消息被静默丢弃**

- 位置：`MessageDeliverer.deliverOfflineQueue` L218-228
- `session.getSubscriptions().get(m.getTopic())` 用**具体 topic** 查订阅表，但订阅表 key 是 **topic filter**（如 `pk/dn/down/#`）。设备用通配符订阅（最常见用法）时永远查不到 → `continue` 跳过 → 消息已被 `popOffline` 从 Redis 删除 → **彻底丢失**。
- 影响：所有离线指令补投功能对通配订阅失效，下行可靠性核心承诺失效。

**P0-2 QoS1 上行 PUBACK 先于持久化，消息丢失但设备认为已成功**

- 位置：`MqttChannelInboundHandler.handlePublish` case 1（L409-412）→ `MessageDeliverer.deliver` → `KafkaEventProducer.send`
- `kafkaProducer.send()` 是异步 fire-and-forget，失败仅在回调里记日志；随后立即 `sendPubAck`。Kafka 不可用/超时/缓冲满时，设备收到 PUBACK 停止重传，消息实际未入 Kafka → **QoS1 名存实亡，退化为 at-most-once**。
- 对储能场景（告警/事件上报）这是不可接受的数据丢失。

**P0-3 多处阻塞调用发生在 Netty IO 线程，违反自身线程模型**

- `completeConnect` 经 `channel.eventLoop().execute()` 回投后，在 **eventLoop 上**执行 `sessionStore.existsSession()`（L343）、`sessionStore.setString(connLock)`（L317）——阻塞 Redis 网络 IO。
- `deliverLocal` → `sessionStore.pushOffline()`（L103，3 次 Redis RTT）被 IO 线程上的 `handlePublish` 直接调用。
- `retainedStore.put()` → Redis SET（L47）同样在 IO 线程。
- `KafkaEventProducer.send`：`BUFFER_MEMORY_CONFIG=64MB` 打满后 `producer.send()` 阻塞至 `max.block.ms`（**未配置，默认 60s**）——Kafka 故障时全部 EventLoop 卡死，**整个节点所有连接不可用**，级联雪崩。
- 后果：Redis/Kafka 抖动直接表现为全节点连接面停摆，这是自研 Broker 最典型的事故模式。

**P0-4 订阅匹配 O(N) 线性扫描，且在 IO 线程执行**

- 位置：`LocalSubscriberIndex.match`（L60-73）——遍历全部 topicFilter 逐条 `TopicMatcher.matches`（内含两次 `String.split` 数组分配）。
- 本平台订阅模型是每设备订阅自己的 `{pk}/{dn}/down/#`，50 万连接 ≈ 50 万 filter。**每条上行 PUBLISH 都要扫 50 万条目**（上行 topic 根本不会命中任何 down filter，纯属空转），单条消息匹配成本 O(N)×split 开销，CPU 直接打满。
- 代码注释自认 "Phase 1 §4.6 规划 trie，本阶段先保证正确性" —— 这是当前**单节点吞吐的第一瓶颈**，规模设备接入不可行。同问题：`removeAll` O(N)、`SessionRegistry.unregisterByChannel` O(N)（大批量断连时 O(N²)）、`RetainedMessageStore.match` O(N)。

**P0-5 优雅停机发送的 MQTT5 DISCONNECT 是畸形报文**

- 位置：`MqttBrokerServer.stop` L106-108
- `new MqttFixedHeader(DISCONNECT, false, AT_MOST_ONCE, false, 0x8B)` —— `MqttFixedHeader` 第 5 个参数是 **remainingLength**，不是 reason code。实际发出的是 remainingLength=139 的 DISCONNECT，v5 客户端会按协议继续读 139 字节 → 解析异常/连接卡死。0x8B（服务器关闭）应走 v5 VariableHeader 属性。

### P1 —— 影响规模扩展与可靠性

**P1-1 Kafka 全量 fan-out 路由架构存在硬天花板**

- `RouterConsumer` 每节点唯一消费组消费全部 24 分区 ⇒ 集群 N 节点时，Kafka 出口流量 = (N + 业务消费组数) × 全集群上行总量；且业务摄取（energy-access）与 Broker 路由**共用同一 topic**，互相耦合。
- 每节点单线程 `RouterConsumer.run()` 串行处理全集群路由消息（JSON 反序列化 + Base64 解码 + deliverLocal + 可能的 Redis 离线入队），单线程上限约数万 msg/s，路由延迟 P99≤10ms 的目标在节点数增加后不可能达成。
- 信封 JSON+Base64 体积膨胀 33%+，CPU/带宽浪费。

**P1-2 RouterConsumer 消费语义导致下行指令可丢失**

- `ENABLE_AUTO_COMMIT=true`（200ms）：poll 后处理中宕机 → offset 已提交 → 消息丢失。
- `AUTO_OFFSET_RESET=latest`：新节点/新 nodeId 上线直接从最新位点消费，积压的在途下行指令全部跳过。
- 下行控制指令（储能 PCS 启停/功率调节）走这条链路，丢失即事故。

**P1-3 resendInflight 的 QoS2 状态机错误 + 恢复期间消息可丢**

- 位置：`MessageDeliverer.resendInflight` L191-215
- QoS2 重发 PUBLISH(dup) 后 state 统一置 `STATE_AWAITING_PUBACK`（L199），但 `handlePubRec` 只认 `STATE_AWAITING_PUBREC` → PUBREC 到达后不发 PUBREL → **该消息永久卡死，inflight 泄漏**。
- 恢复时先把 Redis inflight 加载到内存并重发，末尾 `deleteInflight` 清库（L214）——重发后、ACK 前再次断连，消息丢失。
- PUBREL 续传分支用了 `msg.getPacketId()`（正确），与重发分支的新 packetId 混用，逻辑需整体梳理。

**P1-4 离线队列 Redis 操作非原子**

- `pushOffline`：rightPush → size → leftPop 三次 RTT，容量检查在写入后，并发下超限；应用 `RPUSH + LTRIM` Lua 原子化。
- `popOffline`：LRANGE → DELETE 两步非原子，两步之间新入队的消息被 DELETE 一并删除 → 丢失。应用 Lua（LRANGE+DEL）或 RPOPLPUSH 语义。

**P1-5 下行无背压，慢设备可打爆堆外内存**

- `writeToChannel` 直接 `writeAndFlush`，从不检查 `channel.isWritable()`。弱网/休眠设备（储能柜常见 4G 弱信号）TCP 接收窗口为 0 时，消息在 ChannelOutboundBuffer 无限堆积（水位线只置位不阻断），单设备可堆积至 OOM。
- 无 per-connection 发送队列上限、无超限断连策略。
- `SO_RCVBUF/SO_SNDBUF=256KB` × 50 万连接的内核 buffer 规划过大（EMQX 默认 16-64KB 量级），单机内存模型不成立。

**P1-6 保留消息冷启动丢失 + 跨节点覆盖无序**

- `RetainedMessageStore.cache` 只在"本节点经手的写入"时填充，**启动时不从 Redis 预热**：节点重启/扩容新节点后，在收到下一条 retained 发布前，新订阅投不到任何既有保留消息。新 nodeId 的 RouterConsumer 从 latest 起消费，永远补不上历史 retained。
- 跨节点 retained 写入是 last-writer-wins 且无时间戳，时钟漂移下旧值可能覆盖新值。

**P1-7 认证封禁机制失效且可被放大攻击**

- `authFailureBanSeconds=300` 配置**从未被使用**（全仓库仅定义处出现）——封禁一旦触发永不解除，直到某次成功（被禁设备永远进不来，运维事故）；反之真正的暴力破解仅靠计数没有时长语义。
- `failCounters` 是无界 `ConcurrentHashMap`，攻击者用海量随机 clientId 打认证接口可造成内存膨胀（OOME 向量）。
- 封禁计数仅本节点内存，攻击者在多节点间轮询即可绕过。

**P1-8 in-flight 上限配置未生效**

- `maxInflightPerSession=64` 从未被引用（grep 仅定义处）。`Session.allocPacketId` 只按 65535 封顶。单会话最多 65535 条 × 平均 payload 的内存占用无闸，异常设备/下行风暴下单会话即可吃数 GB 堆内存。

**P1-9 连接锁机制存在多个竞态与过期缺陷**

- `tryAcquireConnLock` 失败后 `getConnLockOwner → kickRemote → setString 覆盖` 非原子；两节点同时接管同一 clientId 时互相 KICK 抖动（踢线风暴）。
- 锁 TTL = sessionTtl（7 天）且**无续期**：长连接超过 7 天锁自动失效，第三节点可误认为无占用。
- `releaseConnLockIfOwner` GET+DEL 非原子（应 Lua 校验后删），竞态窗口可误删新 owner 的锁。
- KICK 信封经 Kafka 异步传递，窗口期内新旧会话双活，下行重复投递。

**P1-10 可观测性未达到生产准入**

- 仅 `BrokerStats` 6 个 AtomicLong + `/internal/broker/stats` 端点。无 Micrometer/Prometheus exporter、无 per-topic 流量、无 PUBACK 时延直方图、无 brokerExecutor 队列深度/拒绝数、无 Kafka producer buffer 占用/consumer lag、无 Redis 延迟、无链路追踪（无 traceId/messageId 贯通）、无告警规则。故障时完全抓瞎。

**P1-11 MQTT 5.0 只是"容忍"而非"支持"**

- 接受 version=5 连接，但：忽略 Session Expiry Interval（固定 7 天）、无 Receive Maximum（流量整形缺失）、无 Topic Alias、无 Reason Code、无 Will Delay（`delaySeconds` 恒为 0）、不支持 AUTH 增强认证、`$share/{group}/` 仅剥前缀做普通匹配 → **共享订阅语义错误**（组内全部成员都会收到，应为组内负载均衡投递一份）。
- 对 v3.1.1 客户端，`MqttProperties.NO_PROPERTIES` 经 Netty 编码器是否多写 0x00 属性长度字节（CONNACK/PUBLISH）需互操作实测验证 —— 若属实则 v3 客户端全部解析异常，列为上线前必验项。

**P1-12 TLS 仅单向认证，缺设备侧证书体系**

- `SslContextBuilder.forServer` 未配 `clientAuth`，无 mTLS、无设备证书签发/吊销（CRL/OCSP）。当前靠 HMAC secret 兜底（可接受，与阿里云一致），但高安全场景（电网合规）需要 mTLS；默认 `tls.enabled=false` 明文 1883 暴露。

### P2 —— 优化项

| #     | 问题                                                                                                         | 位置                                    |
| ----- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| P2-1  | keepalive=0 设备仍被预置 IdleStateHandler(90s) 踢线（协议违规，应移除 idle handler）                                         | `completeConnect` L334                |
| P2-2  | 已认证连接重复 CONNECT 未按规范强制关断                                                                                   | `handleConnect` 无 session 存在性检查       |
| P2-3  | 遗嘱 `willMessage()` 按 UTF-8 String 提取，二进制载荷损坏；遗嘱不持久化，节点宕机丢失；Will Delay 未实现                                  | `handleConnect` L259-267              |
| P2-4  | 空 clientId 一律拒绝；MQTT 3.1.1 允许 cleanSession=1 时空 clientId 由 Broker 分配                                       | `handleConnect` L247                  |
| P2-5  | 每条消息堆内 `byte[]` 拷贝 + 每订阅者 `Unpooled.wrappedBuffer` 分配 + JSON/Base64 信封，GC 压力大；未显式启用 PooledByteBufAllocator | `handlePublish` L394 / `buildPublish` |
| P2-6  | 凭据吊销最长 30min 生效（cache:cred 无失效广播通道）                                                                        | `DeviceAuthService`                   |
| P2-7  | 无 per-clientId/per-tenant 速率限制与配额，单设备可无限速 publish                                                          | 全局缺失                                  |
| P2-8  | 认证风暴无防护：CONNECT 洪峰会打满 brokerExecutor 队列（10000）导致正常认证被丢弃                                                    | `handleConnect`                       |
| P2-9  | retained 跨节点 last-writer-wins 无时间戳                                                                         | `RetainedMessageStore.put`            |
| P2-10 | 测试缺失：仅 4 个工具类单测，无协议互操作/故障注入/压测资产                                                                           | `src/test`                            |
| P2-11 | NIO 传输在 Linux 生产环境应切 Epoll（注释已提及但未落地配置）                                                                    | `NettyServerConfig`                   |

---

## 四、具体优化方案

### P0-1 离线队列补发丢消息

- **当前实现**：`deliverOfflineQueue` 用具体 topic 精确 get 订阅表（key=filter）。
- **风险**：通配订阅下离线指令 100% 丢失。
- **方案**：遍历 `session.getSubscriptions().values()`，用 `TopicMatcher.matches(m.getTopic(), sub.getTopicFilter())` 求匹配 filter 的最高 QoS；改为先投递成功再从 Redis 确认删除（pop 改 Lua：LRANGE+DEL 已返回即删的语义需保留，但投递失败要重新入队）。
- **技术选型**：现有 TopicMatcher 即可；配合 P1-4 的 Lua 原子化。
- **改造成本**：0.5 人日（含单测）。

### P0-2 PUBACK 时序

- **当前实现**：Kafka 异步发送后立即 PUBACK。
- **风险**：QoS1 数据丢失。
- **方案**：QoS1 上行改为 Kafka `send(record, callback)`，**在 callback 成功后才回 PUBACK**；失败按可配置策略：NACK(v5 reason code) / 关闭连接迫使设备重连重发 / 落入本地磁盘 spill 队列（RocketMQ 延迟兜底或 Chronicle Queue）。设备侧需容忍 PUBACK 延迟增加（linger.ms=5 + Kafka RTT，P99 预计 20-50ms，可接受）。
- **技术选型**：保留 kafka-clients；spill 用 Chronicle Queue（嵌入式、零依赖）。
- **改造成本**：2-3 人日（含背压联调）。

### P0-3 IO 线程阻塞

- **方案**：
  1. `completeConnect` 中 `existsSession`/`setString(connLock)` 移入 executor，CONNACK 发送也放入 executor 回调（sessionPresent 可异步计算后再发，协议允许 CONNACK 略有延迟）；
  2. `pushOffline`/`retainedStore.put` 的 Redis 写移入 executor（投递路径只留内存操作）；
  3. `KafkaEventProducer` 增加 `MAX_BLOCK_MS_CONFIG=200` + `DELIVERY_TIMEOUT_MS_CONFIG` 上限，buffer 打满快速失败走降级（计数+告警+消息入 spill 队列），**绝不允许 IO 线程被 Kafka 阻塞**；
  4. 引入静态防护：在 `handlePublish` 入口检测当前线程为 EventLoop 时禁止任何 Redis/Kafka 同步调用（可写 Netty `ResourceLeakDetector` 式的断言 + 单测守护）。
- **改造成本**：2 人日。

### P0-4 订阅匹配 O(N)

- **方案**：实现 **topic trie 订阅树**（EMQX 同款思路）：按 `/` 分层建树，`+`/`#` 通配节点独立挂载，匹配时按 topic 层级下行查找，复杂度 O(层级数) ≈ O(5)；filter → bindings 用 `ConcurrentHashMap` 叶子挂载。索引变更（SUB/UNSUB）走 CopyOnWrite 或读写锁。`SessionRegistry` 增加 channel→deviceKey 反向索引（`channel.attr(DEVICE_KEY_ATTR)` 已有，直接用，删掉 O(N) 扫描）。
- **技术选型**：自研 trie（约 300 行）或引入 Apache Commons `PatriciaTrie` 改造；不建议直接依赖 EMQX 的 mria。
- **改造成本**：3-5 人日（含并发正确性单测 + JMH 基准）。

### P0-5 停机 DISCONNECT 畸形报文

- **方案**：v5 设备用 `MqttMessage` + `MqttReasonCodeAndPropertiesVariableHeader`（或含 properties 的 VariableHeader）构造 reason=0x8B 的 DISCONNECT；v3 设备直接 close。注意 Netty 对 v5 DISCONNECT 的编码支持版本，必要时手写 ByteBuf。
- **改造成本**：0.5 人日。

### P1-1 fan-out 路由天花板（架构改造，阶段二实施）

- **方案（两步走）**：
  1. **Topic 分离**：业务上行摄取独立 topic `iot-device-up`（energy-access 专用消费组，可水平扩展），`mqtt.router` 只承载 Broker 间路由 + 下行；
  2. **下行定向化**：`iot:online:{deviceId}` 已存 brokerNode —— 下行指令按 nodeId 定向发送（每节点一个 `mqtt.down.{nodeId}` topic 或同 topic key=nodeId + 分区路由），消除全量 fan-out；上行跨节点订阅场景（本平台设备只订自己的 down topic，**实际上行几乎不需要跨节点投递**）经评估后可大幅收窄 fan-out 范围。
- **收益**：Kafka 出口流量从 (N+1)×总量 降到 ≈1×总量 + 下行量；单节点消费线程只处理本节点相关消息。
- **改造成本**：5-8 人日（含 energy-access/energy-command 联动改造）。

### P1-2 消费可靠性

- **方案**：`ENABLE_AUTO_COMMIT=false`，处理完成后 `commitSync/commitAsync`；`AUTO_OFFSET_RESET=earliest`（新节点补齐）；消费处理幂等（deviceId+msgId 去重表，Redis SETNX 5min）；多线程化：按分区数开 4-8 个消费线程（每线程独立 consumer 实例）或单 poll + 内存分发到 worker 池（保序按 deviceKey hash）。
- **改造成本**：3 人日。

### P1-3 QoS2 续传修复

- **方案**：QoS2 重发 PUBLISH(dup) 后 state 置 `STATE_AWAITING_PUBREC`；`deleteInflight` 改为逐条 ACK 后删（重发成功即重新 `saveInflight` 新 packetId）；补充 PUBREC 到达但 state 不符时的防御分支（重发 PUBREL）。
- **改造成本**：1 人日。

### P1-4 Redis 原子化

- **方案**：离线队列 `RPUSH + LTRIM` 合并为 Lua 脚本（原子、1 次 RTT）；`popOffline` 改 Lua（LRANGE+DEL 原子）或改为投递确认后 LTRIM 裁剪；`releaseConnLockIfOwner` 改 Lua（GET+ compare + DEL）；连接锁 TTL 缩短至 60s + 心跳续期（复用 online 续期链路）。
- **改造成本**：1-2 人日。

### P1-5 下行背压

- **方案**：`writeToChannel` 前检查 `channel.isWritable()`；不可写时：QoS0 丢弃（计数），QoS1/2 转 offline/inflight 暂缓发送，挂 `ChannelWritabilityChanged` 监听恢复推送；设置单连接最大挂起字节数（如 1MB）超限主动断连（保护性踢线）；`SO_RCVBUF/SNDBUF` 降到 32KB；百万连接场景补 `PooledByteBufAllocator` + direct memory 规划（-XX:MaxDirectMemorySize）。
- **改造成本**：3 人日。

### P1-6 保留消息冷启动

- **方案**：启动时 SCAN `mqtt:retained:*` 批量预热内存缓存（分批 pipeline，10 万条约秒级）；retained 写入携带发布时间戳，冲突时比较时间戳；新节点加入时强制全量预热后再开放订阅。
- **改造成本**：1 人日。

### P1-7 认证封禁修复

- **方案**：封禁状态写 Redis（`mqtt:ban:{clientId}`，TTL=authFailureBanSeconds），计数器也用 Redis INCR+EXPIRE（跨节点共享）；本地保留 Caffeine 小缓存（10k 上限 + LRU 淘汰）挡重复请求；failCounters 加最大容量 + 定时清理。
- **改造成本**：1-2 人日。

### P1-8 inflight 上限落地

- **方案**：`deliverToSession` 在 `outboundInflight.size() >= maxInflightPerSession` 时转离线队列或丢弃（按 QoS 分级策略），不再依赖 65535 自然顶。
- **改造成本**：0.5 人日。

### P1-10 可观测性

- **方案**：引入 Micrometer + Prometheus registry，指标清单：
  - 连接：当前连接数（Gauge）、CONNECT 成功率、认证失败率、封禁数
  - 消息：in/out TPS、per-type（PUBLISH/SUB...）计数、QoS 分布、跨节点路由量
  - 时延：PUBACK 时延直方图（P50/P99/P999）、路由端到端时延
  - 资源：brokerExecutor 队列深度/活跃线程/拒绝数、Kafka producer buffer 占用/consumer lag、Redis 命令时延、JVM GC/堆/direct memory
  - 业务：离线队列长度 TOP、inflight 堆积 TOP、在线设备数（按 tenant/product 维度 tag）
- 日志：关键路径埋 messageId/traceId（deviceId+packetId+ts 生成），接 SkyWalking/OTel；告警规则：连接数突降 >20%、PUBACK P99 > 500ms、consumer lag > 1万、executor 拒绝 > 0。
- **改造成本**：3-5 人日。

### P1-11 MQTT5 补全（按优先级）

Session Expiry Interval → Receive Maximum → Reason Code → Will Delay → Topic Alias → $share 分组负载均衡（Redis 记录 group 成员，轮询投递）。AUTH 增强认证如无业务诉求可不做。

- **改造成本**：逐项 1-3 人日。

### P1-12 mTLS

- **方案**：`SslContextBuilder.clientAuth(ClientAuth.REQUIRE)` + `trustManager` 挂设备 CA；设备证书 CN=deviceKey 与 clientId 绑定校验；证书生命周期接入现有 `iot_device_credential` 表（类型字段区分 secret/cert）；吊销用 CRL 定时刷新或 OCSP stapling。
- **改造成本**：5-8 人日（含 CA 体系与 SDK 改造）。

---

## 五、生产级 MQTT Broker 目标架构设计

```
                        ┌──────────── LVS/NLB (TCP 负载均衡, 源地址散列) ────────────┐
                        │                │                     │                     │
                  ┌─────▼─────┐   ┌──────▼─────┐       ┌──────▼─────┐        …… N 节点
                  │ Broker-1  │   │ Broker-2   │       │ Broker-N   │
                  │ Epoll ELG │   │            │       │            │
                  │ Trie订阅树 │   │            │       │            │
                  └─────┬─────┘   └──────┬─────┘       └──────┬─────┘
                        │                │                     │
        ┌───────────────┼────────────────┼─────────────────────┼──────────────┐
        ▼               ▼                ▼                     ▼              ▼
  ┌───────────┐  ┌──────────────────────────────────┐   ┌──────────────────────┐
  │Redis      │  │ Kafka Cluster                     │   │ Prometheus/Grafana   │
  │Cluster    │  │  iot-device-up (业务摄取, 按设备    │   │ + Alertmanager       │
  │- session  │  │   hash 分区, 消费组水平扩展)        │   │ + SkyWalking         │
  │- subs     │  │  mqtt.down.{nodeId} (下行定向,      │   └──────────────────────┘
  │- inflight │  │   每节点一个 topic/消费组)          │
  │- offline  │  │  mqtt.router (仅保留真正需要         │
  │- connLock │  │   跨节点的订阅路由, 收窄)           │
  │- ban/nonce│  │  iot-device-lifecycle             │
  └───────────┘  └──────────┬───────────────────────┘
                            ▼
              energy-access / energy-command / 影子服务 / TSDB 摄取
```

核心设计决策：

1. **下行定向投递**：command 服务查 `iot:online:{deviceId}` 得 nodeId → 发 `mqtt.down.{nodeId}` → 只有目标节点消费。离线设备进 Redis 离线队列 + lifecycle OFFLINE 事件触发 command 服务落待办。
2. **上行与路由分离**：上行只发 `iot-device-up`（1 次生产），业务消费组各自扩展；Broker 间路由仅在确有跨节点订阅时发生（本平台订阅模型下近乎为零，路由层退化为兜底）。
3. **会话外置 Redis Cluster**：现状延续，补 Lua 原子化 + 锁续期 + 分片规划（按 deviceKey hash 天然分散）。
4. **Device Shadow / Command Queue**：影子服务消费 lifecycle + up/property 维护影子；command 服务维护指令状态机（PENDING→SENT→ACKED/EXPIRED），QoS1 + 业务层 ACK topic（`up/ack` 已在 ACL 白名单内，链路现成）双确认；超时重试 + 离线转 Command Queue。
5. **可靠性分级**：属性上报 QoS1 + 摄取幂等（deviceId+seq 去重）；控制指令 QoS1 + 业务 ACK + 超时重发 + 死信告警；告警事件 QoS1 + spill 兜底。
6. **容量目标**：单节点 25 万连接 / 5 万 msg/s（Epoll + trie + 背压后可达成），4 节点 100 万连接。

---

## 六、下一步改造路线图

> **执行状态（2026-08-10）**：阶段 1 代码项全部完成；阶段 2 的 Epoll / 定向路由 / 多线程消费 / 二进制信封 / **MQTT5 语义补全 / mTLS / 追踪告警** 全部完成（含 0fa638d）；剩余为速率限制、压测与互操作矩阵、真机 mTLS 联测，以及阶段 3 集群验证。

### 阶段 1：达到可生产（目标：5 万连接 / 1 万 msg/s，3-4 周）

| 周    | 事项                                                                                        | 验收标准                                        |
| ---- | ----------------------------------------------------------------------------------------- | ------------------------------------------- |
| W1   | 修全部 P0：离线队列匹配、PUBACK 时序（callback 后回 ACK）、IO 线程阻塞清理（含 Kafka max.block.ms）、停机 DISCONNECT 报文 | 故障注入（杀 Kafka/Redis）不丢已 ACK 消息、EventLoop 无阻塞 |
| W1-2 | trie 订阅索引 + channel 反查索引替换 O(N) 扫描                                                        | JMH：50 万 filter 下单次匹配 < 10μs                |
| W2   | 下行背压（isWritable + 超限踢线）、inflight 上限落地、离线队列/锁 Lua 原子化、resendInflight QoS2 修复               | 慢消费者场景 direct memory 稳定                     |
| W2-3 | 认证封禁 Redis 化 + ban TTL 生效、failCounters 容量封顶、keepalive=0 修复、重复 CONNECT 关断                  | 协议一致性测试通过                                   |
| W3   | Micrometer+Prometheus 全量指标 + Grafana 面板 + 基础告警                                            | 指标清单全部可查                                    |
| W3-4 | 压测与稳定性验证（见下）+ v3.1.1/v5 客户端互操作矩阵（Mosquitto/paho/EMQX SDK）                                 | 5 万连接 72h 稳定，PUBACK P99 < 100ms             |

### 阶段 2：支持十万设备（目标：单节点 10 万连接 / 3 万 msg/s，4-6 周）

1. EpollEventLoopGroup + Linux 参数调优（fd 100 万、somaxconn、tcp_tw_reuse、net.core.netdev_max_backlog）；socket buffer 32KB。
2. **Topic 分离 + 下行定向投递**（mqtt.down.{nodeId}），fan-out 收窄；RouterConsumer 多线程化（4-8 consumer）+ 手动提交 + earliest。
3. 信封二进制化（Protobuf）替换 JSON+Base64。
4. mTLS 设备证书体系（如合规需要）；per-tenant/per-device 速率限制（令牌桶，Redis 或本地 Caffeine）。
5. MQTT5 补全：Session Expiry / Receive Maximum / Reason Code / $share 负载均衡。
6. 混沌测试常态化：Broker 节点宕机、Redis 主从切换、Kafka broker 滚动重启、网络分区（tc/netem）、消息积压 100 万回放。
7. 2-3 节点集群上线，验证会话跟随设备（杀节点后设备重连恢复订阅/inflight/离线队列）。

### 阶段 3：支持百万设备（8-12 周）

1. 4-6 节点集群 + NLB 源地址散列；单节点 25 万连接调优（内存模型：每连接 ≤ 4KB 应用态，堆 16G + direct 8G 起步）。
2. Redis Cluster 分片 + 会话数据容量规划（100 万持久会话 × 2KB ≈ 2GB，订阅/inflight 另计）；Redis 不可用时的降级策略（只读接入、禁离线队列）。
3. Kafka 分区扩容（iot-device-up 48-96 分区）+ 跨 AZ 部署（rack awareness）。
4. 百万连接压测（emqtt-bench 分布式压测集群，≥16 压测机）；7×24 长跑 + 每日故障注入。
5. **战略评估点**：此阶段对比自研总成本（人力/风险/运维）与 EMQX 商业版/开源版 + 规则引擎外置方案。若团队 Broker 专职人力 < 2 人，建议百万规模直接采用 EMQX 集群，自研 Broker 退居边缘/私有化轻量场景。

### 稳定性测试方案（贯穿各阶段）

| 场景                                | 工具                              | 指标                            |
| --------------------------------- | ------------------------------- | ----------------------------- |
| 百万连接建立（conn rate 5000/s 爬坡）       | emqtt-bench（分布式）                | 连接成功率 ≥99.99%，CONNACK P99     |
| 高并发 publish（QoS0/1 混合，10 万 msg/s） | emqtt-bench pub + 自定义 Netty 客户端 | 端到端延迟分布、丢消息率=0（已 ACK 口径）      |
| 弱网/慢消费者                           | tc netem（延迟/丢包/限速）              | direct memory 水位、背压触发率、踢线准确性  |
| Broker 节点宕机                       | kill -9 + 集群编排                  | 设备 30s 内重连恢复、inflight/离线消息零丢失 |
| Redis/Kafka 异常                    | 宕机/网络隔离/磁盘写满                    | 接入面可用（降级）、无 IO 线程阻塞、spill 生效  |
| 消息积压                              | 停消费 1h 后恢复                      | lag 追平时间、无 OOM                |
| JVM 压力                            | 堆 80% 占用下压测 + GC 日志             | Full GC 频率、STW < 200ms        |
| 长稳                                | 7×24 背景流量 + 定时故障注入              | 内存无泄漏（Old 区平稳）、连接无泄漏（fd 数平稳）  |

### 与成熟 Broker 的核心能力差距小结（2026-08-10 更新）

| 能力         | EMQX/HiveMQ/VerneMQ    | 阶段 1+2 修复后                            | 差距等级                           |
| ---------- | ---------------------- | ------------------------------------- | ------------------------------ |
| 订阅路由       | trie/ETS 索引 O(depth)   | trie 订阅树 O(depth) + channel O(1) 反查   | **已消除**（原 P0-4）                |
| 集群 session | 内置迁移/共享（mria/raft）     | Redis 外置 + Lua 原子化 + 60s 锁续期          | 小（方向一致）                        |
| 可靠投递       | 完整背压+持久化会话队列           | 下行背压 + inflight 上限 + 离线队列 Lua         | **已消除**（原 P0-1/P1-4/P1-5/P1-8） |
| 跨节点路由      | —                      | 定向投递 mqtt.down.{nodeId}（fan-out 消除）   | **已消除**（原 P1-1/P1-2）           |
| 可观测        | $SYS + Prometheus + 追踪 | Micrometer + Prometheus（连接/消息/时延/线程池） | 小（原 P1-10，缺链路追踪）               |
| MQTT5      | 全量                     | $share 负载均衡 + Session Expiry + Receive Max + Will Delay（Topic Alias/AUTH 延后） | 小（原 P1-11）                     |
| 共享订阅       | 组内负载均衡                 | $share 组内轮询投递（P1-11）                  | **已消除**                        |
| 限速/配额      | 内置 zone/listener 级     | per-deviceKey 发布限速（P2-7，per-tenant 维度未做） | 小                              |
| 安全         | mTLS/PSK/JWT/CRL       | HMAC + mTLS（CN=clientId 绑定，待真机联测）   | 小（原 P1-12）                     |
| 运维         | CLI/Dashboard/热配置/滚动升级 | 无                                     | 大（未修）                          |

---

## 七、修复进展对照（阶段 1 + 阶段 2 完成后，2026-08-10）

> 对照基准 = 本报告第三、四章原始评估。状态图例：✅ 已修复 · 🟡 部分修复 · ⬜ 未修复。  
> 对应 commit：`b5b9276`（阶段 1 修复）、`1f2b591`（阶段 2 定向路由/传输/二进制化）、`a7a39f2`（Prometheus+冒烟）。

### 7.1 P0 —— 5/5 全部修复

| #    | 问题                       | 状态 | 修复方式（代码落点）                                                                                                                                                                 | 验证                               |
| ---- | ------------------------ | -- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| P0-1 | 离线队列补发通配订阅丢消息            | ✅  | `MessageDeliverer.deliverOfflineQueue` 改为 `TopicMatcher` 遍历订阅 filter 匹配，取最高授予 QoS，无匹配才跳过                                                                                   | 冒烟：持久会话断线重连 sessionPresent=1     |
| P0-2 | QoS1 PUBACK 先于 Kafka 持久化 | ✅  | 上行写 `mqtt.uplink`，PUBACK 推迟到 Kafka `acks=all` 回调成功之后（`KafkaEventProducer.sendBytes` onSuccess）；失败关连接迫使设备重传                                                                 | 冒烟：PUBACK 仅在 Kafka 确认后返回，实测 49ms |
| P0-3 | IO 线程 Redis/Kafka 阻塞     | ✅  | CONNECT 慢路径（认证/连接锁/sessionPresent）全量移入 `brokerExecutor`，回投 eventLoop 仅注册会话；`pushOffline`/retained 写/离线通知异步化；producer 加 `max.block.ms=200` + `delivery.timeout.ms=10s` 快速失败 | 运行日志无 EventLoop 阻塞告警             |
| P0-4 | 订阅匹配 O(N) 线性扫描           | ✅  | `LocalSubscriberIndex` 重写为 topic trie（O(层级数)，+/# 通配、$ 规则、$share 剥前缀、deviceKey→filters 反向索引）；`SessionRegistry.unregisterIfChannelMatches` O(1)                              | `LocalSubscriberIndexTest` 10 用例 |
| P0-5 | 停机 DISCONNECT 畸形报文       | ✅  | `MqttBrokerServer.stop` 改用 `MqttReasonCodeAndPropertiesVariableHeader((byte)0x8B)` 正确编码 v5 reason code                                                                     | 优雅停机路径验证                         |

### 7.2 P1 —— 12/12 已修复

| #     | 问题                        | 状态 | 修复方式                                                                                                                                        | 说明                                                         |
| ----- | ------------------------- | -- | ------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| P1-1  | fan-out 路由硬天花板            | ✅  | 阶段 2 架构改造：上行 `mqtt.uplink`（access 唯一消费组）+ 下行 `mqtt.down.{nodeId}` 定向 + `mqtt.broadcast` 兜底，`mqtt.router` 仅兼容期                               | Kafka 出口 (N+1)×总量 → ≈1×总量；`RouterConsumer` 三通道独立消费组        |
| P1-2  | 消费语义丢下行                   | ✅  | `BytesKafkaConsumerEngine`：手动提交（整批处理完 commitSync）+ `earliest` + 多线程分区并行 + 单条毒丸进 DLQ                                                         | 重启续读不丢停机窗口消息                                               |
| P1-3  | resendInflight QoS2 状态机错误 | ✅  | QoS2 重发后 state 置 `STATE_AWAITING_PUBREC`；按新 packetId 重新持久化而非末尾清库；`handlePubRec` 增加重复 PUBREC 防御分支                                            | 单测覆盖状态转换                                                   |
| P1-4  | 离线队列/连接锁非原子               | ✅  | `SessionStore`：pushOffline（RPUSH+LTRIM+EXPIRE）、popOffline（LRANGE+DEL）、releaseConnLockIfOwner（compare+del）全部 Lua 单脚本原子                       | 原子化消除竞态                                                    |
| P1-5  | 下行无背压                     | ✅  | `Session.pendingWrites` 挂起队列（上限 `max-pending-messages-per-session=1000`）+ `channelWritabilityChanged` 冲刷；超限 QoS0 丢弃、QoS1/2 保留 inflight 重连续传 | 慢设备不再打爆 direct memory（SO_RCVBUF/SNDBUF 256KB 未降，🟡）        |
| P1-6  | 保留消息冷启动丢失                 | ✅  | `RetainedMessageStore.warmUp()` 启动 SCAN `mqtt:retained:*` 预热内存缓存                                                                            | 跨节点覆盖时间戳未做（🟡）                                             |
| P1-7  | 认证封禁机制失效                  | ✅  | 封禁/计数 Redis 化（`mqtt:ban:{clientId}` TTL 生效、`mqtt:authfail` INCR+EXPIRE 跨节点共享）；本地快表 10 万容量封顶                                                 | Redis-key 规范已补登                                            |
| P1-8  | inflight 上限未生效            | ✅  | `deliverToSession` 强制执行 `max-inflight-per-session=64`，超限持久会话转离线队列、干净会话丢弃                                                                    | 与 P0-1 链路互补                                                |
| P1-9  | 连接锁竞态/过期                  | ✅  | Lua compare+del 释放；TTL 7 天 → 60s 短租约 + `renewOnline` 心跳续期（Lua refreshConnLockIfOwner）                                                       | 踢线风暴/双活窗口未完全消除（🟡）                                         |
| P1-10 | 可观测性缺失                    | ✅  | `BrokerMetrics`：连接/订阅 Gauge、消息/认证失败/路由失败/背压/inflight 计数、brokerExecutor 线程池水位、`mqtt_uplink_puback_latency` 直方图；`/actuator/prometheus` 暴露     | 链路追踪（traceId 日志关联）+ 8 条 Prometheus 告警规则（0fa638d）；OTel 全链路追踪延后 |
| P1-11 | MQTT5 仅容忍级                | ✅  | 0fa638d：$share 共享订阅组内负载均衡（轮询+max QoS）、Session Expiry Interval 持久化（=0 断开即删）、Receive Maximum 作为 inflight 上限、Will Delay 延迟投递（窗口内重连取消） | v5 冒烟通过（expiry=0 重连 sessionPresent=0、默认持久保留、PUBACK 75ms）；单测 +4 用例；Topic Alias/AUTH 无业务诉求延后 |
| P1-12 | TLS 仅单向认证                 | ✅  | 0fa638d：clientAuth(REQUIRE)+trustManager(设备 CA)；握手后校验设备证书 CN=clientId；gen-mqtt-certs.sh 扩展 -c/-d 签发设备证书 | 配置 tls.client-auth / trust-cert-file；CN 不匹配拒绝接入；需真机 mTLS 联测 |

### 7.3 P2 —— 9/11 已修复，2 项标注为环境/平台约束

| #     | 问题                   | 状态 | 说明                                                                                               |
| ----- | -------------------- | -- | ------------------------------------------------------------------------------------------------ |
| P2-1  | keepalive=0 误踢       | ✅  | 移除预置 IdleStateHandler                                                                            |
| P2-2  | 重复 CONNECT 未关断       | ✅  | 按 MQTT-3.1.0-2 规范关断                                                                              |
| P2-3  | 遗嘱二进制/持久化/Will Delay | ✅  | willMessageInBytes 提取原始字节（90a6027）；Will Delay 已随 P1-11；遗嘱持久化到 Redis 为后续增强 |
| P2-4  | 空 clientId 拒绝        | ✅  | 平台认证绑定 clientId（HMAC username 内嵌），空 clientId 无法认证且构成匿名接入风险，维持拒绝（决策注释） |
| P2-5  | GC/ByteBuf 压力        | ✅  | 信封二进制化 + 显式 PooledByteBufAllocator（90a6027）                                                      |
| P2-6  | 凭据吊销延迟 30min         | ✅  | Redis pub/sub mqtt:cred:revoked：删 cache:cred + 踢线强制重认证（90a6027），吊销缩到秒级 |
| P2-7  | 速率限制/配额              | ✅  | PublishRateLimiter 固定窗口令牌桶（90a6027），超限 QoS0 丢/QoS1 关连接，桶容量封顶            |
| P2-8  | 认证风暴防护               | ✅  | 认证并发信号量 auth-max-concurrent（90a6027），超限快速拒绝保护 executor                         |
| P2-9  | retained 跨节点覆盖无序     | ✅  | RetainedEntry 携带 ts + Lua 新者胜写入（90a6027）                                                    |
| P2-10 | 测试缺失                 | 🟡 | 单测 4 → 74（common 27 / broker 37 / access 14）；压测与 v3/v5 互操作矩阵为环境项（阶段 3）                |
| P2-11 | Linux 未用 Epoll       | ✅  | `TransportFactory`：Epoll → KQueue（反射）→ NIO 自适应；netty 统一 4.1.109；epoll 依赖 optional（生产补原生 jar 即启用） |

### 7.4 新增能力（阶段 2 引入，原评估未覆盖）

| 能力         | 说明                                                                                               |
| ---------- | ------------------------------------------------------------------------------------------------ |
| 下行定向投递     | `mqtt.down.{nodeId}` 按 `mqtt:conn:{deviceKey}` owner 寻址，仅目标节点消费；离线回落 Redis 离线队列 / broadcast      |
| 上行与路由分离    | `mqtt.uplink` 24 分区 key=deviceKey，access 唯一消费组，Broker 零 fan-out                                  |
| 二进制信封      | `RouterEnvelopeCodec`（magic 0xE9 0x01 + 定长头 + 原始 payload，零 Base64 膨胀），decode 自动探测二进制/JSON 平滑滚动升级 |
| 多通道消费引擎    | `BytesKafkaConsumerEngine` 三通道独立消费组（down/broadcast/legacy），分区并行 + 手动提交 + DLQ                     |
| topic 自动扩容 | `KafkaTopicInitializer` 对已存在 topic 分区不足走 `createPartitions` 显式扩容                                 |

### 7.5 验证结论

- **单元测试**：70 全绿（含阶段 2 新增 codec 5 用例、trie 10 用例）。
- **真实环境端到端**（MQTT 端口 18831，Kafka/Redis/Nacos/MySQL 在线）：
  - HMAC 认证 CONNECT ✓ → SUBACK ✓ → QoS1 上行 PUBACK（Kafka 确认后，49ms）✓
  - 非优雅断线重连 sessionPresent=1（持久会话恢复）✓
  - 越权发布被 ACL 拒绝关连接 ✓
  - 下行定向信封经 `mqtt.down.broker-1` 到达设备（Node 脚本复刻二进制信封验证）✓
  - Prometheus 指标计数正确（messages_in / messages_out / crossnode / uplink 消费组 lag=0）✓
- **待补验证**：v3.1.1/v5 客户端互操作矩阵、emqtt-bench 压测（5 万连接/1 万 msg/s）、Kafka 故障注入。

---

*报告基于 commit 当前工作区代码，所有行号引用 `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/` 下源文件。*
