# EnergyX 物联网平台 · 面试高频问题与参考回答

> 适用：**Java 9 年经验 · IoT 平台方向**（架构师/技术负责人/资深后端）
> 定位：以「当前项目 EnergyX IoT 平台」为主线，覆盖面试官最可能追问的 **OTA、场景联动、自研 MQTT Broker 高可用/高并发、消息可靠性、最难问题** 等话题。
> 说明：储能业务（EMS 充放电策略/收益核算/需量管理）不在本文范围。
> 速用方式：每题先看「一句话答案」建立骨架，再读「完整回答」补充细节；「追问防御」预演面试官下一刀。

---

## 目录

- [0. 项目自我介绍（30 秒 / 1 分钟两版）](#0-项目自我介绍)
- [1. 系统架构类](#1-系统架构类)
- [2. 自研 MQTT Broker（核心亮点）](#2-自研-mqtt-broker核心亮点)
- [3. 消息链路与可靠性](#3-消息链路与可靠性)
- [4. OTA 升级设计](#4-ota-升级设计)
- [5. 场景联动 / 规则引擎设计模式](#5-场景联动--规则引擎设计模式)
- [6. 高并发与性能优化](#6-高并发与性能优化)
- [7. 分布式与可靠性通用问题](#7-分布式与可靠性通用问题)
- [8. 最难的问题（重点准备）](#8-最难的问题重点准备)
- [9. 开放性 / 防御类问题](#9-开放性--防御类问题)

---

## 0. 项目自我介绍

### Q0-1 用 30 秒介绍这个项目（电梯陈述）

> 我目前在做一个**企业级物联网接入平台**，核心是自己从零写的 **MQTT Broker 集群**：基于 Netty 实现 MQTT 3.1.1/5.0 双协议、QoS 全等级，采用「无状态节点 + Redis/Kafka 状态外置」的架构。高可用靠四件事：**跨节点连接锁**（Redis SETNX 短租约，节点宕机设备重连任意节点即可接管会话）、**节点心跳**（死节点被判定后下行自动回落）、**会话跟随设备**（订阅/inflight/离线队列全部持久化在 Redis，重连即恢复）、**优雅停机**（删心跳+批量释放锁）。可靠性上，QoS1/2 的 PUBACK/PUBCOMP **必须等 Kafka 持久化确认后才回**，失败就关连接让设备重传；下行做 inflight 续传 + 离线队列。性能上有一条铁律：**Netty EventLoop 上零阻塞**，所有 Redis/MySQL/Kafka 慢路径都剥离到业务线程池，订阅匹配用 topic trie，单节点目标 25 万连接 / 5 万 msg/s。在上面搭了完整的 IoT 中台能力：物模型校验、设备影子、指令中心（状态机+幂等+离线补发）、告警中心、场景联动规则引擎、OTA 升级中心（灰度+差分+断点续传）。

### Q0-2 用 1 分钟介绍（含项目规模与角色）

> 项目是一个多租户 IoT 平台，**12+ 个微服务**（设计拆分 16 个领域服务），消息总线是 Kafka（15 个 topic），存储是 MySQL + TDengine 时序库 + Redis Cluster + Elasticsearch。我担任**技术负责人/全栈开发**，负责整体架构设计和最核心的接入层。
> 我主导的部分有三块：**① 自研 MQTT Broker**（连接管理、认证 ACL、QoS 状态机、集群高可用），这是平台的技术差异点；**② 统一消息管线**（设备 → Broker → Kafka → 物模型标准化 → 影子/时序/告警/规则，指令 ACK 反向闭环），核心解决"不丢不重、按设备保序"；**③ 上层业务中台**（场景联动规则引擎、OTA 升级中心、命令中心）。
> 工程上配套做了：压测工具 + 5 个故障演练脚本（Kafka 重平衡、Broker 重启自愈、Redis/MySQL 降级、控制链路 P99 回归）、74 个单元测试、Prometheus 指标管线。**成果**：Broker 故障恢复收敛到 30 秒内，QoS1 上行 PUBACK 实测 49ms，集群目标支持 100 万连接。

> 💡 **9 年经验的话术加分点**：自我介绍后主动补一句技术取舍观——"这个项目里我比较坚持的三个原则：**能外置的状态不放在内存、能异步的调用不阻塞 IO、能幂等的边界都做幂等**"，把话题引向自己熟悉的领域。

---

## 1. 系统架构类

### Q1-1 讲讲整体架构？为什么这么分层？

**一句话**：七层架构——设备层 → 接入层（自研 Broker）→ 消息层（Kafka 总线）→ 协议适配层（access）→ 领域服务层（影子/指令/告警/规则/OTA/TSDB）→ 网关层 → 前端，核心思想是 **"MQTT 只管设备到 Broker 这一段，出了 Broker 全走 Kafka"**。

**完整回答**：

| 层 | 组件 | 职责 |
|---|---|---|
| 设备层 | 储能设备 + 自研 SDK | HMAC 认证、属性/事件上报、指令 ACK、断线指数退避重连 |
| 接入层 | 自研 Netty MQTT Broker 集群 | 协议解析、认证 ACL、QoS 状态机、会话管理、跨节点路由 |
| 消息层 | Kafka（15 topic） | 全链路事件总线，削峰、解耦、多消费组扇出、按设备分区保序 |
| 适配层 | energy-access | 物模型校验、消息标准化、上/下行桥接翻译 |
| 领域层 | tsdb/shadow/command/alarm/rule/ota/notify | 时序存储、影子、指令、告警、规则引擎、OTA、通知 |
| 网关层 | Spring Cloud Gateway | JWT 验签、身份头透传、TraceId 透传、路由 |
| 前端 | Vue3 驾驶舱/管理端 | 可视化运维 |

**关键设计决策（讲 2~3 个就够）**：
1. **接入与业务解耦**：Broker 是「MQTT 接入 + Kafka 桥」一体，后端服务全部是 Kafka 消费者，不订阅 MQTT 主题 → Broker 可以独立压测、独立扩缩容，业务升级不动接入层。
2. **事件驱动拆分**：同步调用只用于低频读（Feign），写/通知全部走 Kafka 事件 → 12+ 个服务之间没有强依赖，符合 DDD 领域边界。
3. **存储各司其职**：MySQL 权威数据、TDengine 时序、Redis 热状态/过程态、ES 检索 → 见 ADR-005「Redis 只存可重建的过程态」。

**追问防御**：
- **"为什么不用 EMQX？"** → 见 Q2-8。
- **"12+ 个服务会不会太重？"** → 承认单体前期更快，但我们目标百万级设备、多团队并行、独立扩缩容，事件驱动下拆分成本可控；如果团队小，模块化单体 + 消息队列也是合理起点（参考 ADR-004 备选方案）。

### Q1-2 微服务之间怎么通信？

**一句话**：**读走 Feign（经网关 lb://），写/通知走 Kafka 事件**，不引入 OpenFeign 服务间直连之外的同步强依赖。

**完整回答**：
- 同步读：规则引擎查设备、OTA 回写版本、命令中心解析设备，用 Feign + Nacos 服务发现，每个 FeignClient 配 **FallbackFactory 降级**，降级后走缓存或直接失败。
- 异步写：所有跨服务的状态变更都以 Kafka 事件表达（属性上报、生命周期、命令 ACK、告警、OTA 上行），消费方各自幂等。
- 好处：链路天然削峰（设备上报洪峰不会打垮业务服务）、故障隔离（下游挂了消息先积压不丢）、多消费组扇出（一条设备上报同时被 tsdb/shadow/alarm/rule 消费）。

**追问防御**：
- **"为什么不全部异步？"** → 同步读延迟低、实现简单，适合低频管理面（如创建命令前查设备）；高频数据面全异步。
- **"Feign 挂了怎么办？"** → FallbackFactory + 超时配置；核心链路（设备上报）不依赖 Feign，纯 Kafka。

### Q1-3 网关层做了什么？

**一句话**：Spring Cloud Gateway 做**统一鉴权门卫**——JWT 验签（jjwt HS）、身份头透传、TraceId 透传、白名单，业务服务不信任客户端自报身份。

**完整回答**（`GlobalAuthFilter`）：
- 白名单（登录/验证码/actuator/WebSocket 升级）直接放行；其余请求必须 `Authorization: Bearer <JWT>`。
- 验签 + 过期/issuer 校验（40100/40101 区分），通过后把 `x-user-id / x-user-name / x-tenant-id / x-enterprise-id` 写入请求头透传下游——**业务侧只认网关注入的头**，防止伪造。
- 用户权限走 Spring Security RBAC（`@ss.hasPermi` 权限点，对齐若依语义）；会话令牌是 **JWT(仅身份+会话id) + Redis 会话** 双轨：完整 LoginUser 含权限 Set 存 Redis `auth:login_token:{sid}`，每请求 `sid → Redis → SecurityContext`。
- 选这套而不是纯 JWT 的原因：**权限变更实时生效**——禁用用户、改角色立即踢下线，不用等 token 过期（ADR-010）。

**追问防御**：
- **"每请求查 Redis 会不会慢？"** → Redis 本地 0.1~0.3ms，相对业务调用可忽略；换来的是权限实时吊销，安全性优先。
- **"WebSocket 怎么鉴权？"** → 浏览器 WS 带不了 Authorization 头，网关放行 `/ws` 升级请求，token 走查询参数，下游 `WsAuthInterceptor` 自己验签。

### Q1-4 多租户怎么隔离？

**一句话**：MyBatis-Plus **行级租户**（`ConditionalTenantLineHandler` 自动给 SQL 拼 tenant_id 条件）+ 网关身份头注入租户上下文，天然多企业隔离。

**完整回答**：网关把 JWT 里的 tenantId 透传 → `TenantContextFilter` 写入 ThreadLocal → MyBatis-Plus 租户插件在增删改查自动拼接 `tenant_id` 条件，业务代码零感知。明细流水表（如 OTA 任务明细）不含租户列，用复合主键避免误拼。Redis 键也带租户/设备维度。

---

## 2. 自研 MQTT Broker（核心亮点）

### Q2-1 Broker 整体架构是怎么设计的？

**一句话**：**无状态节点 + 状态外置**——Broker 节点内存只放热 Session，所有可重建的持久态（订阅/inflight/离线队列/遗嘱/在线标记）都在 Redis，跨节点消息统一走 Kafka，节点间零网状依赖。

**完整回答**（对照 `backend/energy-mqtt-broker`）：
- 设备经 LB 打到集群任一节点，节点间**无共享内存**，靠 Redis（会话/锁/心跳/凭据/在线态）+ Kafka（跨节点路由/生命周期事件）协同。
- **"会话跟随设备"**：设备重连被 LB 打到任意节点，都能从 Redis 重建持久会话 → 天然支持水平扩容。
- 跨节点投递统一走 Kafka topic，避免节点间直连的网状拓扑：
  - `mqtt.uplink`（上行摄取，access 唯一消费组）
  - `mqtt.down.{nodeId}`（下行定向，仅目标节点消费）
  - `mqtt.broadcast`（KICK 踢线/owner 缺失兜底）
- 内部模块：`handler`（协议分发）/ `auth`（认证+ACL）/ `session`（内存 Session + Redis 持久态）/ `routing`（本地投递 + Kafka 定向路由）/ `retained` / `lifecycle` / `stats`。

### Q2-2 Broker 怎么做到高可用？（面试必问）

**一句话**：五大机制——**① 跨节点连接锁接管；② 节点心跳死亡判定；③ 会话跟随设备；④ 优雅停机主动让位；⑤ 下行定向路由 + 离线/广播回落**。

**完整回答**（逐项讲，面试官最吃这套）：

| # | 机制 | 实现 | 解决什么 |
|---|---|---|---|
| 1 | **连接锁接管** | Redis `mqtt:conn:{deviceKey}` = nodeId，SETNX 短租约 **TTL 20s** + 心跳续期（Lua `REFRESH_LOCK` 仅 owner 可续，随在线续期链路刷新）；新连接先查 owner：存活则发 KICK 信封踢远端，判定死亡则直接 `overwriteConnLock` 接管 | 防止同 clientId 双连接（异地重连/节点宕机） |
| 2 | **节点心跳** | `NodeHeartbeatScheduler` 每 10s 写 `mqtt:node:{nodeId}`（TTL 30s） | 下行寻址时 owner 心跳缺失 → 判死节点 → 回落广播/离线队列，规避"无人消费的分区" |
| 3 | **会话跟随** | 订阅/inflight/离线队列/遗嘱持久化到 Redis（`mqtt:session/subs/inflight/offline/will`） | 节点宕机设备重连任意节点 → `runSessionRestore` 加载订阅、按状态续传 inflight、补发离线队列 |
| 4 | **优雅停机** | `stop()`：关 acceptor → 发 v5 DISCONNECT(0x8B) → 停消费 → flush producer → 删心跳 + **批量释放本节点连接锁** | 其他节点立即接管，不等锁 TTL 过期（停机时长 ≈ 0） |
| 5 | **定向路由回落** | 下行按 owner 发 `mqtt.down.{nodeId}`；owner 缺失且持久会话 → Redis 离线队列；无会话/竞态 → `mqtt.broadcast` 兜底 | 单条下行只进目标节点，离线不丢 |

**追问防御**：
- **"连接锁 TTL 只有 20s，长连接不会误判吗？"** → 在线设备有 MQTT 心跳/keepalive 续期链路，续期时顺带 Lua 刷新连接锁（`refreshConnLockIfOwner`，仅 owner 可续），所以锁不会无故过期；只有真宕机（心跳停）才会被接管。
- **"两个节点同时接管怎么办？"** → 释放用 Lua 校验 owner（GET==owner 才 DEL），接管是 overwrite + 发 KICK；极端竞态下靠 Kafka 广播 KICK 信封收敛，单测覆盖过踢线风暴场景。
- **"Redis 本身挂了怎么办？"** → fail-closed：新连接认证直接拒绝（`AuthResult.deny(3)`），**既有内存会话继续存活**（会话在内存，Redis 只存持久态），进程/端口不受影响，故障演练 03 验证过。这是刻意的取舍：宁可拒绝新连接也不让设备进入无认证状态。

### Q2-3 Broker 怎么做到高并发？（面试必问）

**一句话**：**线程模型铁律（IO 零阻塞）+ 订阅匹配 trie + 下行背压 + 发布限流 + 二进制信封**，单节点目标 25 万连接 / 5 万 msg/s。

**完整回答**：

1. **线程模型铁律**（最核心）：
   - Netty EventLoop 上**禁止任何 Redis/MySQL/Kafka 同步阻塞**；认证、会话持久化、Kafka 生产全部经 `brokerExecutor` 业务线程池剥离，结果回投 IO 线程。
   - 拒绝策略选 **"记日志丢弃"而非 `CallerRunsPolicy`**——后者会让 IO 线程内联执行阻塞任务，卡死整个 EventLoop（这是踩过坑才改的，见 Q8-2）。
   - Kafka producer 配 `max.block.ms=200` + `delivery.timeout.ms=10s` 快速失败，**绝不允许 buffer 打满阻塞 IO 线程 60 秒**（这是 P0-3 事故根因）。
2. **订阅匹配 topic trie**：`LocalSubscriberIndex` 按 `/` 分层建树，`+`/`#` 通配节点挂载，匹配复杂度从 **O(N)（扫 50 万 filter）降到 O(层级数)**；`$share` 共享订阅组内轮询选一；channel→deviceKey 反向索引 O(1)。
3. **下行背压**：发送前检查 `channel.isWritable()`，不可写进 `pendingWrites` 挂起队列（上限 1000），`channelWritabilityChanged` 恢复冲刷；超限 QoS0 丢弃、QoS1/2 转 inflight/离线——**弱网设备（4G 储能柜）不再打爆堆外内存**。
4. **发布限流**：`PublishRateLimiter` 固定窗口令牌桶（per-deviceKey），超限 QoS0 丢 / QoS1 关连接；认证并发用信号量 `auth-max-concurrent` 防 CONNECT 风暴。
5. **二进制信封**：跨节点路由用 `RouterEnvelopeCodec`（magic 0xE9 0x01 + 定长头 + 原始 payload），替代 JSON+Base64（膨胀 33%+），decode 自动探测二进制/JSON 平滑滚动升级。
6. **IO 优化**：Epoll → KQueue → NIO 自适应；`PooledByteBufAllocator`；每连接绑定一个 EventLoop 保证写序。

**追问防御**：
- **"EventLoop 阻塞有什么现象？"** → 一个连接的 Redis 阻塞会让该 EventLoop 上所有连接全部卡住，表现是"全节点连接面停摆"，这是自研 Broker 最典型的事故模式。
- **"25 万连接的内存模型？"** → 每连接应用态 ≤ 4KB 估算：SocketChannel + Session 对象 + 少量 ByteBuf，堆 16G + direct 8G 起步；`max-connections` 准入控制在 TCP 层（`channelActive` 计数），超限直接拒绝。
- **"为什么不用原生 epoll 库？"** → netty-transport-native-epoll 依赖 optional，生产补原生 jar 即启用，代码侧 `TransportFactory` 已自适应，开发机（Windows/Mac）不引入原生依赖。

### Q2-4 QoS 状态机怎么实现的？可靠语义从哪来？

**一句话**：上行 **QoS1/2 的 PUBACK/PUBCOMP 只在 Kafka 持久化回调成功后才回**（路由失败关连接迫使设备重传，at-least-once）；下行 inflight 持久化支持断线续传；QoS2 入站"收 PUBREL 才二次路由"保证恰好一次。

**完整回答**：
- **上行 QoS1**：PUBLISH → 校验 dup/pktId → `MessageDeliverer.deliver` → Kafka send 回调：成功才发 PUBACK；失败关连接 → 设备重连重传。
- **上行 QoS2**：PUBLISH → 发 PUBREC → 缓存 InboundPublish[pktId] → **收到 PUBREL 才二次路由**（恰好一次）→ Kafka 持久化确认后发 PUBCOMP；重复 PUBREL 直接回 PUBCOMP（幂等）。
- **下行 QoS1/2**：`Session.outboundInflight` 缓存待确认报文，状态机 AWAIT_PUBACK / AWAIT_PUBREC / AWAIT_PUBCOMP；持久会话同步 Redis `mqtt:inflight`，重连后 `resendInflight` 按状态续传（AWAITING_PUBACK 重发 PUBLISH(dup)、AWAITING_PUBREC 重发 PUBLISH(dup)、AWAITING_PUBCOMP 重发 PUBREL）。
- **为什么 PUBACK 等 Kafka 而不是立即回**：Kafka 不可用时立即回 PUBACK = 消息已丢但设备以为成功（**QoS1 名存实亡，退化为 at-most-once**），这是 P0-2 的真实事故，后来改成回调后才回。
- **语义分层**：MQTT QoS 只保证报文层语义，业务层的"不重不漏"靠 commandId 幂等 + 消费端去重兜底（Q3-2 展开）。

**追问防御**：
- **"PUBACK 延迟 49ms 会不会太慢？"** → 这是 Kafka acks=all + linger 的代价，储能场景可接受（遥测秒级，指令 500ms 级）；换来的是"已 ACK 必不丢"的强承诺，压测工具按"已 ACK 口径"断言丢消息率=0。
- **"QoS2 恰好一次会不会很重？"** → 控制面默认 QoS1 + 业务幂等（ADR-009），QoS2 供需要强语义的报文使用，吞吐可接受。

### Q2-5 设备认证怎么做？怎么防伪造/重放？

**一句话**：**一机一密 HMAC-SHA256**：username 三段式（clientId&ts&nonce），password = HMAC(deviceSecret, username)；±2min 时间窗 + nonce 一次性消费防重放；连续失败封禁；可选 mTLS（证书 CN 必须等于 clientId）。

**完整回答**：
- 凭据：`clientId = {productKey}_{deviceName}`，deviceSecret 只在平台和设备端。
- 防重放：`timestamp` 毫秒 ±2min 窗口 + `nonce` 经 Redis SETNX 一次性消费（TTL 5min）。
- 防爆破：连续失败 ≥10 次 → `mqtt:ban:{clientId}` 封禁 300s，**跨节点共享**（Redis 计数 INCR+EXPIRE，Lua 原子），自然过期解封。
- 凭据三级缓存：Redis 30min → MySQL 兜底 → 回写，MySQL 挂不影响已缓存设备接入；凭据吊销通过 Redis pub/sub `mqtt:cred:revoked` 秒级踢线重认证。
- 业务闭环：认证同时校验设备状态机（禁用/封禁/未激活/吊销/过期）。
- **Topic ACL 白名单**：`canPublish` 仅限本设备 `up/{type}`、`ota/{type}`；`canSubscribe` 仅限本设备 `down/*`、`ota/down`——防止一台设备伪装成别的设备。
- 高级：mTLS（clientAuth=REQUIRE + 证书 CN=clientId 校验）。

**追问防御**：
- **"为什么不用 CA 证书做唯一认证？"** → HMAC 与阿里云"一机一密"同级，设备端实现成本低（MCU 也能算 HMAC）；mTLS 留给高安全场景（电网合规），代码已支持、按配置启用。
- **"时间戳窗口内重放怎么办？"** → nonce 一次性消费兜底，同一签名只能成功一次。

### Q2-6 设备断线重连 / 弱网怎么容错？

**一句话**：**三件事**：① 持久会话（订阅/inflight/离线队列在 Redis，重连任意节点恢复）；② 离线消息队列（`mqtt:offline`，RPUSH+LTRIM 限容量）；③ 命令离线队列（`iot:cmd:q`，上线事件触发补发）。

**完整回答**：
- 在线权威：Redis `iot:online:{deviceId}` = brokerNode，TTL 30s 心跳续期；MySQL `status` 仅审计视图（**双权威设计**，实时判定不查库）。
- 弱网判定：Broker 连接权威 + MQTT keepalive 1.5× 超时（HEARTBEAT_TIMEOUT）识别静默断连，不依赖 TCP FIN。
- 离线队列原子化：push 用 Lua（RPUSH+LTRIM+EXPIRE 单 RTT），pop 用 Lua（LRANGE+DEL），避免"两步之间新消息被误删"（P1-4 踩坑后修复）。
- 离线补发语义：`deliverOfflineQueue` 用 TopicMatcher 遍历**订阅 filter** 匹配（不是用具体 topic 精确查表——那是 P0-1 的通配订阅丢消息事故）。
- 生命周期事件：ONLINE/OFFLINE 发 `iot-device-lifecycle`，被 shadow（刷在线态）、command（补发离线命令）、ota（补推升级）、rule（上下线触发）消费。

### Q2-7 水平扩容怎么做？

**一句话**：**加节点即可**——节点无状态、会话在 Redis、路由走 Kafka 定向 topic；LB 新节点接入后，设备重连自然分布过去，下行按连接锁 owner 定向到新节点。

**完整回答**：
- 新增节点：nodeId 唯一，`NodeHeartbeatScheduler` 上线即写心跳，`mqtt.down.{newNodeId}` topic 由 `KafkaTopicInitializer` 自动创建。
- 设备迁移：无需迁移——设备重连（LB 轮询/最少连接）落到新节点，从 Redis 重建会话。
- 扩容注意点：Kafka 分区数要 >= 消费并发；`mqtt.uplink` 24 分区 key=deviceKey 保证单设备有序；Redis Cluster 会话数据按 deviceKey hash 天然分散。
- 容量路线：单节点 25 万连接（Epoll + trie + 背压），4 节点 100 万连接。

### Q2-8 为什么自研 Broker 而不直接用 EMQX？（高频灵魂拷问）

**一句话**：**三个理由——可控性、定制性、成本；同时诚实承认 EMQX 的运维成熟度优势，百万级会做战略评估**。

**完整回答**：
1. **协议/语义全链路可控**：QoS 状态机、设备级 ACL、物模型 topic 路由、储能指令 ACK 语义都能深度定制；EMQX 是黑盒，出问题只能等社区/商业支持。
2. **平台差异化与团队成长**：这是平台最核心的技术差异化（面试亮点），也让团队真正理解 MQTT 协议栈。
3. **无商业依赖**：EMQX 商业版按连接数收费，百万级 License 成本高；开源版集群运维（mria/raft）同样不轻松。
4. **诚实的边界**：自研代价是运维工具链（无 Dashboard/热配置）和可靠性验证成本；文档里明确写了 **阶段 3 战略评估点：若 Broker 专职人力 < 2 人，百万规模直接采用 EMQX 集群，自研退居轻量场景**——这句话在面试里说出来非常加分，说明你不是盲目自研。

**追问防御**：
- **"自研的可靠性怎么证明？"** → 74 个单测 + 5 个故障演练（Broker 重启 120s 自愈、Redis/MySQL fail-closed、Kafka 重平衡、控制 P99 回归）+ 二进制信封平滑升级 + 压测资产（seed/connect/throughput/control 四子命令）。

---

## 3. 消息链路与可靠性

### Q3-1 设备上行的完整链路？

**一句话**：设备 PUBLISH → Broker（ACL/QoS 状态机/限流）→ 本地订阅者投递 + 写 `mqtt.uplink`（key=deviceKey）→ access 唯一消费组摄取 → 物模型校验/标准化 → 按类型扇出到 tsdb/shadow/alarm/rule/command-ack。

**完整回答**：
```
设备 → MQTT Broker（HMAC 认证 → ACL → QoS 状态机 → 限流）
     → Kafka mqtt.uplink（key=deviceKey 分区保序，enable.idempotence + acks=all）
     → energy-access：MessageDedup 幂等去重 → 物模型校验（ModelValidator）→ 标准化
        ├─ iot-thing-property → tsdb(TDengine) / shadow / alarm / rule
        ├─ iot-thing-event    → tsdb / alarm
        ├─ iot-command-ack    → command（执行回写）/ ems
        └─ iot-device-lifecycle → command（离线补发）/ ota / shadow / rule
```
- **为什么 Broker 直接写业务 topic？** Broker 只做"接入 + 桥"，不做业务解析；物模型标准化在 access 做，Broker 保持纯净。
- **OTA 特殊通道**：`ota/` 前缀的报文在 access **原样透传**到 `ota.uplink`，不进入物模型标准化链路（避免污染物模型）。

### Q3-2 消息怎么保证不丢不重？（必问）

**一句话**：**三层防护**——① 消费组互斥（同 groupId 分区互斥，不重复消费）；② 生产侧幂等（enable.idempotence + acks=all，Broker 单节点入站）；③ 重放去重（各消费边界 MessageDedup Redis SETNX + 业务幂等）。

**完整回答**：
1. **并发不重复**：同服务多实例共用同一 groupId，Kafka 消费组把分区互斥分配给组内成员——一个分区同一时刻只被一个实例消费。
2. **Broker 不制造重复**：单条 MQTT 消息只入站一个 broker 节点 + 生产侧幂等（`enable.idempotence` + `acks=all`），Kafka 自身幂等去重。
3. **重放不重复（at-least-once 兜底）**：崩溃/重连后 Kafka 重发，由各消费边界拦截：
   - access/tsdb 用 `MessageDedup`（Redis SETNX，key=`iot:msg:dedup:{stage}:{device_id}:{message_id}`，TTL 300s）——**按 stage 分命名空间**很关键：一条报文会经过多个消费边界，每个边界的 Kafka 交付都可能各自重放，共用一个 key 会把下游合法消息误判为重复。
   - command/ems/alarm 用**业务幂等**：条件状态迁移（WHERE state ∈ 合法前驱）、雪花唯一键、覆盖式 UPDATE。
- **保序**：`mqtt.uplink` 按 deviceId 分区 → 单分区单线程消费 → 同设备消息天然有序；规则引擎同设备串行处理。

**追问防御**：
- **"SETNX 去重会不会误删/漏删？"** → TTL 300s 覆盖 Kafka 重放窗口；按 stage 隔离避免跨边界误判；去重只挡重放，不挡业务。
- **"at-least-once 意味着可能重复投递？"** → 是的，但每个消费边界都有幂等兜底，对外呈现"恰好一次"的语义效果（QoS1 上行同理）。

### Q3-3 指令下行的完整链路？（控制面可靠性）

**一句话**：平台创建指令（commandId 幂等）→ 落库 CREATED → 在线直发 `iot-command-down`（key=deviceId）→ access 按连接锁 owner 定向投递 `mqtt.down.{nodeId}`（或广播兜底）→ Broker 推给设备 → 设备回 ACK 走上行回到 command 服务 → 状态机收敛；离线设备入 `iot:cmd:q` 队列，上线事件触发补发。

**完整回答**：
- **幂等三层**：创建幂等（commandId 即幂等键，SETNX 24h，重复创建返回既有指令）；下发幂等（仅 state=CREATED 才置 SENT 投递）；ACK 幂等（条件 UPDATE，重放自然空操作）。
- **状态机**：CREATED → SENT → DEVICE_RECEIVED → EXECUTING → SUCCESS/FAILED/TIMEOUT；超时由 `CommandTimeoutScanner` 扫描驱动重试（上限熔断）或置终态。
- **离线分流**：设备离线 → 入 Redis `iot:cmd:q:{deviceId}`（保持 CREATED）→ 设备上线（lifecycle ONLINE 事件）→ `OfflineCommandRedeliverer` 补发。
- **为什么 QoS1 + 业务幂等而不是 QoS2**：MQTT QoS2 只保证报文恰好一次，不保证业务不重放；控制语义必须在业务层以 commandId 收敛（ADR-009），QoS1 吞吐更好。

**追问防御**：
- **"超时重试会不会重复执行？"** → 设备端按 commandId 去重（SDK 内置），平台侧条件状态迁移保证只有前驱状态能进下一态。
- **"指令乱序怎么办？"** → `iot-command-down` 按 deviceId 分区，单设备指令串行投递。

### Q3-4 设备影子（Shadow）是干嘛的？为什么需要？

**一句话**：**reported/desired 双文档 + 版本号乐观锁 + delta 事件**——解决"设备离线时配置要持久化、上线后自动同步"以及"读取属性不查设备"。

**完整回答**：
- reported：设备上报的最新属性（消费 iot-thing-property 写入 Redis `iot:shadow:reported:{deviceId}`）。
- desired：平台期望配置（管理端写入），delta 计算器对比 desired/reported 差异 → 发 `iot-command-down`（setProperties）让设备收敛。
- 版本号乐观锁防并发覆盖；影子读取快（Redis）且离线可用（AWS IoT Shadow 同款心智模型，ADR-007）。
- 规则引擎条件求值也读影子（设备最近属性值），不跨服务查询。

### Q3-5 时序数据怎么设计的？（TSDB）

**一句话**：**TDengine 超级表（STABLE）+ 按设备建子表 + 批量写入缓冲 + 连续查询降采样 + 保留策略**。

**完整回答**：
- 物模型属性打点：按属性标识写入 TDengine 子表（标签 = 设备/产品维度），超级表模型天然契合"设备 × 指标 × 时间窗"查询。
- 写入优化：`TsdbBatchBuffer` 攒批 + `TsdbFlushScheduler` 定时冲刷，避免逐条 insert 的 RTT 开销。
- 降采样：TDengine 连续查询自动聚合（分钟级/小时级），长周期查询不打原始表。
- 保留策略：原始数据 N 天 + 降采样数据长期（`DataRetention` 统一治理，xxl-job 滚动清理）。
- 为什么选 TDengine：写入吞吐极高、资源占用低、国内储能行业事实标准；对比 InfluxDB（高基数短板+集群商业授权）、TimescaleDB（超大规模写入聚合不足）——ADR-002。

---

## 4. OTA 升级设计

> 参考 OTA-面试图解.md + Phase12 设计文档。面试官关注：**架构解耦、灰度、差分、离线兜底、安全、成功判据**。

### Q4-1 OTA 整体架构？为什么这么设计？

**一句话**：**Kafka 是控制面总线，把 OTA 升级中心与设备接入层彻底解耦**——两套 topic 体系：Kafka topic（服务间）与 MQTT topic（设备侧），由 access 桥接翻译；OTA 只做任务编排，不碰设备连接。

**完整回答**（分层讲）：
```
设备 ←MQTT→ Broker ←→ access（桥接翻译） ←Kafka→ energy-ota 升级中心 :8118
                                                  ├─ 包管理（全量/差分/校验/签名）
                                                  ├─ 任务编排（批次/灰度/重试/超时）
                                                  ├─ 定时调度（灰度推进/超时扫描）
                                                  └─ 集成（设备中心回写版本/告警中心通知）
```
- **上行**：设备 publish `ota/inform|progress|result|pull` → Broker → access 按 `ota/` 前缀**原样透传**到 `ota.uplink`（不进入物模型标准化链路）→ ota 唯一消费组。
- **下行**：ota 发 `ota.down.{nodeId}` → access 查 Redis 连接注册表 `mqtt:conn:{deviceKey}=nodeId` 定位设备所在节点 → PUBLISH `{pk}/{dn}/ota/down` → Broker 推给设备。
- **为什么不用命令中心下发 OTA？** OTA 走独立 topic 通道，避免与设备自身升级逻辑冲突、避免物模型命令语义污染（Phase12 §10）。
- **好处**：ota 服务挂了下游消息积压不丢、升级任务与设备连接完全解耦、可独立水平扩展。

### Q4-2 OTA 升级的完整时序？（在线设备）

**一句话**：任务创建（快照设备明细 PENDING）→ 下发通知信封（URL/版本/SHA256/签名/分片参数）→ 设备 HTTPS Range 分片下载（每片 SHA256 校验）→ 进度上报 → 本地 AB 分区升级重启 → **上报新版本号 == 目标版本 → 判成功** → 回写设备中心。

**完整回答**（核心节点）：
1. 管理端创建任务 → 快照目标设备到 `ota_task_device`（state=PENDING），**防止设备变更影响任务一致性**。
2. 在线设备直推：`mqtt.down.{nodeId}` 信封（含 url/version/size/sha256/segmentSize）。
3. 设备 HTTPS Range 分片下载（默认 1MB/片，块级 SHA256，损坏只重传该片；中断按 offset 断点续传）。
4. `ota/progress`（0-100 + DOWNLOADING/UPGRADING 阶段）→ ota 更新明细进度。
5. 本地升级（AB 分区防变砖）→ 重启 → 上线上报 `ota/inform {version: 2.0.0}`。
6. **成功判据：设备上报新版本号 == 目标版本（唯一判据，同阿里云）**——进度 100% 但没上报新版本且超升级超时 → 判失败（防设备假报进度）。
7. Feign 回写 `iot_device.firmware_version` → 刷新统计 → 推进灰度 → 任务完成。

### Q4-3 灰度发布怎么做？升级失败了怎么办？

**一句话**：**1% → 10% → 50% → 100% 渐进推进，每批成功率 < 95% 自动暂停 + 告警**；失败有重试（默认 2 次/5min）、超时扫描、单设备重试、取消/恢复。

**完整回答**：
- 创建任务时可选 task_type：全部设备 / 指定设备 / **灰度比例**（gray_ratio）。
- 灰度初始只下发 `round(device_count × ratio%)` 台（device_id 升序取模）；每 30min 检查已完结设备成功率：≥95% 推进下一档；<95% 或失败率突增 → 自动暂停（status=3）通知人工介入。
- 重试与超时扫描（@Scheduled / xxl-job，1min）：下载超时（默认 60min）或升级超时（默认 30min）→ 重试或置 TIMEOUT；失败且 retry_count < retry_times 且 retry_at 已到 → 重新下发。
- 失败上报（fail_code：DOWNLOAD_FAIL / VERIFY_FAIL / UPGRADE_FAIL / TIMEOUT）→ 投 `iot-alarm` 事件走告警通知链路。

### Q4-4 离线设备怎么升级？

**一句话**：**三条兜底**——① 监听 `iot-device-lifecycle`（ONLINE）上线补推；② 设备主动 `ota/pull` 拉取；③ 升级通知信封走定向下行，离线时消息不丢（access 侧有离线队列语义）。

**完整回答**：
1. **上线补推**：收到上线事件 → 查该设备待升级/下载中任务（state ∈ 0/1）→ 校验当前版本（已 == 目标则直接置成功，避免重复下发）→ 补推通知。
2. **主动拉取**：设备错过通知（异常重启/订阅时序）时 publish `ota/pull {version}` → ota 查询 PENDING 任务 → 有则立即下发，无则返回 204。
3. **重试语义**：离线期间任务明细保持 PENDING，设备上线自然触发补推（双保险）。

### Q4-5 差分升级怎么做？

**一句话**：**bsdiff 生成差分包 + 按设备当前版本精确匹配 + 未命中自动退化全量 + 双 SHA256 校验（差分包自身 + 合并产物 == 全量包）**。

**完整回答**：
- 同表存储：全量包与差分包共用 `ota_package`（package_type=1/2），差分包记录「目标 version + 源 base_version」；同一目标版本可 1 个全量 + N 个差分包。
- 生成：管理端上传全量包后可指定源版本调 bsdiff 生成；也可直接上传厂商预生成差分包（省平台 CPU）。
- 匹配：任务 downloadPolicy=DIFF_FIRST（默认）时按 `product_key + version + base_version + module` 精确匹配设备当前版本，命中下发差分，**未命中自动退化全量（差分缺失永不阻断升级）**；FULL_ONLY 一律全量（兼容不支持 bspatch 的旧设备）。
- 设备端：下载 diff → bspatch 与本地固件流式合并（需约 1.2× 固件临时区）→ **合并产物 SHA256 必须等于全量包 sha256（targetSha256）**→ 通过才允许安装。
- 多跳差分（v1→v1.5→v2 逐跳）暂不支持，跨多版本直接全量。

### Q4-6 OTA 安全性怎么设计？

**一句话**：**HTTPS 传输 + SHA256 完整性 + RSA 签名验签（预留）+ 双 SHA256 防差分篡改 + 版本单调防降级**。

**完整回答**：传输层仅 HTTPS（URL 带签名时效参数）；信封带 sha256 设备下载后校验，不符拒绝安装并上报 VERIFY_FAIL；低端 MCU 无 SHA256 硬件加速时退化为 MD5（信封携带 signMethod）；上传时厂商私钥 RSA 签名、设备预置公钥验签（预留）；**禁止向低于当前版本的包下发**（管理端校验 target > 当前版本，防降级攻击）。

### Q4-7 OTA 有哪些设计取舍/业界对比？（深度追问）

- 与阿里云 IoT 对比：成功判据一致（新版本上报 == 目标）；比阿里云多了**差分升级**和**失败自动暂停**；进度上报阿里云限制 ≥3s 间隔，我们由设备实现侧控制。
- 与华为云对比：华为 NB 场景标配差分；我们重试 2 次/5min 与其建议一致；超时默认 下载 60min/升级 30min 与其一致。
- **为什么不走命令中心下发？** OTA 是"长任务 + 大文件"形态，命令中心是"短指令 + ACK"形态，混在一起会互相污染状态机。
- **升级包存储**：本地存储 + StorageService 抽象，预留对象存储（OSS）切换。

---

## 5. 场景联动 / 规则引擎设计模式

> 参考 Phase11 设计文档 + energy-rule 实现。面试官关注：**模型设计、设计模式、热更新、防抖/恢复、防环、定时触发**。

### Q5-1 场景联动的核心模型是什么？

**一句话**：**TCA 模型（Trigger-Condition-Action）**——触发器多选一（OR）、条件全满足（AND）、动作独立执行；参考阿里云 IoT 场景联动语义，运营人员可自助配置、免发版。

**完整回答**：
```
5 类触发源：属性上报 / 定时 cron / 设备上下线 / 告警事件 / 手动触发
      │（任一命中）
      ▼
条件求值：多条件 AND（设备在线状态 / 时间范围 / 属性比较 / 预留脚本）
      │（全满足）
      ▼
防抖检查（SETNX 窗口内不重复）
      │
      ▼
动作执行：设备控制命令 / 触发告警 / webhook 通知 / 嵌套规则（独立执行互不影响）
```
- 规则 DSL 以 JSON 存 MySQL（dslVersion 预留版本演进），前端可视化编辑器双向映射。
- 为什么选 TCA 而不是 ThingsBoard 节点图 / EMQX SQL：储能场景以「条件判断 → 执行动作」为主，逻辑深度有限但配置量大，TCA 学习成本低、和阿里云语义一致；条件/动作都预留 script 扩展点应对未来复杂逻辑（Phase11 §2 调研结论）。

### Q5-2 规则引擎用了哪些设计模式？（重点准备，9 年经验必问）

**一句话**：**策略模式（触发器/动作/通知渠道）、模板方法（统一引擎处理链）、观察者（Redis pub/sub 热更新）、状态机（恢复边沿）、门面（RuleEngine 统一入口）、多级缓存（RuleCache）**。

**完整回答**（逐个说 + 对应代码）：
1. **策略模式**（用得最重）：
   - 触发器：`TriggerMatcher` 按 type 分发 5 类触发器匹配（PropertyTriggerMatcher / LifecycleTriggerMatcher / AlarmTriggerMatcher / TimerTrigger / ManualTrigger）。
   - 动作：`ActionExecutorService.executeAll` 按 type 分发到 `DeviceCommandAction` / `AlarmAction` / `NotifyAction` / `NestedRuleAction`——**每个动作独立 try-catch，失败不影响其他动作**，结果写执行日志 action_result。
   - 通知渠道：energy-notify 的 `NotifyChannel` 接口 + `EmailExecutor` / `DingTalkExecutor` / `WeComExecutor` / `WebhookExecutor`（策略模式典型用法，加渠道只加一个类）。
   - 新增一种触发/动作类型 = 加一个类 + 注册，不改引擎主流程——这是**开闭原则**的落地。
2. **模板方法**：`RuleEngine.processRule` 是统一的处理链（Trigger OR → Condition AND → 防抖/恢复 → 动作 + 执行日志），属性/生命周期/告警/定时/手动五类入口（onProperty/onLifecycle/onAlarm/onScheduled/onManual）共用，差异只在候选规则来源。
3. **观察者/发布订阅**：规则变更双写 MySQL → 发 Redis pub/sub `rule:changed` → 各实例订阅后**增量刷新进程内缓存**（热更新不重启）。
4. **状态机**：`RecoveryTracker` 维护边沿状态（FIRED → RECOVERED）实现"条件满足→不满足"的恢复动作（不受防抖限制）。
5. **门面模式**：`RuleEngine` 是引擎唯一入口，屏蔽内部组件协作。
6. **多级缓存**：`RuleCache` L1 进程内缓存（Map + 维度索引）+ L2 Redis `rule:cache:{ruleId}`（10min，变更主动删）；执行时快照 RuleConfig 副本，更新不中断在途执行。
7. **索引模式**：按触发维度建索引（propertyIndex 按设备、lifecycleIndex、alarmIndex、timerIndex），事件进来先粗筛候选规则再逐条匹配，避免全量扫规则。

### Q5-3 高频上报怎么防止触发风暴？（防抖）

**一句话**：**Redis SETNX 防抖窗口（rule:debounce:{ruleId}:{deviceId}，默认 300s）+ 恢复动作不受防抖限制**。

**完整回答**：属性触发规则条件满足后，先 SETNX 防抖 key 成功才执行动作，窗口期内高频上报不重复触发；窗口自然过期后允许再次触发。恢复动作（边沿）必须可靠执行，所以**不受防抖限制**。另外 webhook 动作超时 5s 快速失败，执行线程池（核心 4/最大 16/队列 1000）隔离，防 webhook 慢拖垮引擎。

### Q5-4 规则怎么热更新？多实例一致吗？

**一句话**：**进程内缓存 + Redis pub/sub 增量刷新**；配置变更流程：更新 MySQL（乐观锁 version）→ 发 `rule:changed` → 各实例订阅后增量刷新本地缓存，最终一致收敛。

**完整回答**：
- 启动时 `RuleRefreshService.init()` 全量加载 enabled 规则 + 重建维度索引。
- 变更（CRUD/启停）：双写 MySQL + 发布 rule:changed（消息体 ruleId 或 ALL 全量刷新）。
- 停用规则从索引卸载、删除规则顺带删 xxl-job job；更新走 version 乐观锁。
- 执行时快照副本：即使热更新进行中，在途执行不中断（执行用 RuleConfig 副本）。

### Q5-5 定时触发怎么做？多实例会重复执行吗？

**一句话**：**xxl-job 动态注册 job（jobKey=rule-{ruleId}）+ 路由策略「第一个」+ Redis 分布式锁 `lock:scheduled:rule-{ruleId}` 双保险**。

**完整回答**：
- 为什么用 xxl-job 而不是 Quartz：规则编排的 TIMER 天然是"用户随时增删改规则 → 动态注册/注销定时任务"形态，xxl-job 调度中心原生支持任务动态管理 + 可视化运维 + 失败重试（Quartz 需要自建 JobStore + 手动集群锁）。
- 规则含 TIMER 触发器 → 通过 `XxlJobAdminClient` 在调度中心动态创建 job（cron 变化/停用/删除同步增删改）。
- 执行器 `@XxlJob("sceneRuleTimer")` 收到触发 → 解析 ruleId → 构造 RuleContext → 走统一引擎。
- **防重**：调度中心路由策略选「第一个」+ 执行前抢 Redis 分布式锁（Lua SETNX + TTL），双保险防调度重放/路由切换竞态。

### Q5-6 嵌套规则怎么防止死循环？

**一句话**：**深度限制 ≤ 5 + 环检测集合（visitedRuleIds 沿调用链传递，A→B→A 直接拒绝）**；嵌套语义是"跳过目标规则 Trigger 匹配，直接评估其 Conditions"（阿里云 Rule Output 语义）。

**完整回答**：`RuleEngine.executeNested` 每次进入先查深度（>5 拒绝）、再查环（visitedRuleIds contains → 拒绝并记日志告警）；创建/更新规则时校验 RULE 动作的目标 ruleId 存在且不等于自身。业务场景：消防告警 → 联动全站下电（嵌套串联），环检测防止误配置打爆引擎。

### Q5-7 规则引擎和告警引擎什么关系？

**一句话**：**并列协作，不重复造轮子**——告警中心负责"阈值检测/合并/静默/恢复/通知"，规则引擎只做"编排"；规则动作里"触发告警"是调告警中心的 `POST /alarm/trigger`，比较操作符语义下沉到 energy-common 的 `ValueCompareUtils` 共用。

**完整回答**：告警规则引擎（AlarmRuleEngine）消费属性/事件做阈值比较（GT/GTE/LT/LTE/EQ/NEQ，数值优先）→ 产生告警记录 → 合并/静默/恢复 → WebSocket 推送 + ES 归档 + 通知渠道；规则引擎条件求值里复用同一套比较语义（下沉到 common），保证两套引擎行为一致。

---

## 6. 高并发与性能优化

### Q6-1 项目里做过哪些性能优化？效果如何？

**一句话**：**Broker 三层优化（订阅匹配 trie、线程模型零阻塞、fan-out 改定向路由）+ 消费端批量写入 + 缓存降级**。

**完整回答**（挑 2-3 个讲，每个都有量化收益）：
1. **订阅匹配 O(N) → topic trie**：50 万 filter 下每条消息从"扫 50 万条目 + 两次 split"降到 O(层级数)，这是单节点吞吐的第一瓶颈（P0-4）。
2. **fan-out → 定向路由**：Kafka 出口流量从 (N+1)×总量 降到 ≈1×总量（P1-1），跨节点路由 P99 ≤ 10ms 目标才可能达成。
3. **JSON+Base64 信封 → 二进制信封**：体积膨胀 33%+ 消除，decode 自动探测兼容滚动升级。
4. **TDengine 批量写入**：TsdbBatchBuffer 攒批 + 定时冲刷，避免逐条 RTT。
5. **认证凭据三级缓存**：Redis 30min → MySQL 兜底 → 回写，MySQL 挂不影响已缓存设备接入。
6. **规则引擎维度索引**：事件进来先按索引粗筛候选规则，避免全量扫规则。

### Q6-2 压测怎么做的？有什么数据？

**一句话**：自研压测工具 `stress.jar`（seed 造数 / connect 建连 / throughput 吞吐 / control 控制链路），**确定性分片**（worker 按设备区间划分，杜绝并发抢号重叠），配套 5 个故障演练脚本黑盒断言。

**完整回答**：
- 数据口径：单节点目标 25 万连接 / 5 万 msg/s；集群 100 万连接；跨节点路由 P99 ≤ 10ms；控制链路 P99 ≤ 500ms（演练 05 回归基线）。
- 实测：QoS1 上行 PUBACK（Kafka 确认后）49ms；Broker 重启后设备 120s 内自动重连自愈；Redis 宕机新连接 fail-closed 且既有会话存活。
- 演练 5 项：Kafka 重平衡 + LAG 追平 / Broker 重启自愈 / Redis 降级 / MySQL 切换（默认预演模式 + trap 兜底）/ 控制 P99 回归。
- 诚实说明：百万连接实测属于阶段 3 工作（需分布式压测机 + emqtt-bench），当前是架构就绪 + 单节点验证——**面试里主动交代验证边界比被问穿好**。

### Q6-3 线上问题排查思路？（9 年经验常问）

**一句话**：**从链路指标入手，四级定位：网关/服务日志 → Kafka LAG → Redis 键状态 → 时序曲线**，全链路透传 traceId/messageId 把散点串起来。

**完整回答**：
- 先看现象归类：是"接入面"（连接数/认证失败率）还是"处理面"（消费 LAG/落库延迟）还是"查询面"（接口 P99）。
- Broker：`/actuator/prometheus` 指标（连接 Gauge、消息 TPS、PUBACK 时延直方图、brokerExecutor 队列深度、背压/inflight 计数）→ 定位 IO 线程是否被阻塞（日志有 EventLoop 阻塞告警）。
- Kafka：`kafka-consumer-groups` 查 LAG——**LAG 上涨 = 下游处理慢或挂了**，这是消息系统的第一信号。
- Redis：查 `mqtt:conn`（连接锁 owner）、`iot:online`（在线态）、`iot:msg:dedup`（消费进度痕迹）确认状态一致。
- 链路：消费入口透传 traceId（Kafka header 继承），`iot_scene_exec_log` 等执行日志带 traceId，把多服务调用串起来。

---

## 7. 分布式与可靠性通用问题

### Q7-1 分布式锁怎么用的？

**一句话**：Redis Lua SETNX + TTL（Redisson 风格），用在**定时任务防重**（`lock:scheduled:*`）、**OTA 任务推进/取消**（`ota:lock:task:{taskId}`）、**Broker 连接锁**（`mqtt:conn`）三类场景。

**完整回答**：统一走 energy-common 的 `RedisLockUtil`/`DistributedLock`；强调 Broker 连接锁的特殊性——它不是普通互斥锁而是**可接管锁**：owner 心跳消失后新节点直接 overwrite（加 KICK 信封踢远端），配合 Lua 原子化释放（GET==owner 才 DEL）避免误删新 owner。

### Q7-2 幂等设计有哪些套路？

**一句话**：**三类：唯一键（commandId/雪花 ID）、状态机条件迁移（WHERE state ∈ 合法前驱）、Redis SETNX 窗口（去重/防抖/幂等许可）**，按边界选择。

**完整回答**：
- 指令创建：commandId 幂等键（SETNX 24h，重复返回既有）。
- ACK 回写：条件 UPDATE（重放空操作）。
- 消息消费：MessageDedup（按 stage 的 SETNX 窗口）。
- 影子写入：覆盖式 UPDATE + 版本号乐观锁。
- 规则防抖：SETNX 窗口。
- 告警：ruleCode+deviceId 静默窗口。

### Q7-3 分布式事务怎么处理？

**一句话**：**不用分布式事务，全部转最终一致 + 幂等补偿**（ADR-004/005 决策）——Kafka 事件驱动，每个消费边界幂等，必要时对账任务兜底。

**完整回答**：典型场景如"OTA 升级成功 → 回写设备版本 + 刷新统计 + 推进灰度"：不是同一个本地事务，而是 ota 服务消费成功事件后各自幂等处理，失败靠重试扫描 + 告警；设备版本回写 Feign 失败可重试（FallbackFactory）。好处：无 2PC 的性能与可用性代价；代价：短暂不一致窗口，由最终一致收敛 + 审计日志兜底。

### Q7-4 XXL-Job 分布式调度怎么防重、防悬挂？

**一句话**：**调度中心统一触发 + 执行器分布式锁防重 + 任务幂等 + 失败重试**；业务侧如"计划执行"还有状态机防悬挂（超时自动补记终态）。

**完整回答**：项目 7 类定时任务（数据保留滚动清理、指令超时扫描、OTA 超时/灰度推进、规则 TIMER 触发、告警/影子/OTA 日志保留清理）统一 xxl-job；多实例执行前抢 Redis 分布式锁；执行日志/幂等键保证重复触发无害。

---

## 8. 最难的问题（重点准备）

> 面试官必问。准备 **2 个完整故事**（1 个架构级 + 1 个疑难 bug 级），每个按 **背景 → 现象 → 定位 → 方案 → 结果 → 反思** 讲。以下 4 个候选全部来自本项目真实踩坑记录，选最有把握的 2 个。

### 候选 A：QoS1 消息丢失——"PUBACK 先于 Kafka 持久化"（可靠性之痛，最推荐）

- **背景**：自研 Broker 上行链路，设备 QoS1 PUBLISH 后立即回 PUBACK。
- **现象**：Kafka 不可用/超时/缓冲满时，Kafka send 是异步 fire-and-forget（失败只记日志），设备收到 PUBACK 停止重传 → **消息实际没进 Kafka，QoS1 名存实亡，退化为 at-most-once**。对储能场景（告警/事件上报）是数据丢失事故。
- **定位**：review 阶段逐行走查 `handlePublish` → `KafkaEventProducer.send` 时序，发现 PUBACK 在持久化确认之前。
- **方案**：改为 **Kafka send(record, callback)，callback 成功后才回 PUBACK**；失败关连接迫使设备重连重传（at-least-once 语义）；Kafka producer 加 `max.block.ms=200` + `delivery.timeout.ms=10s` 快速失败，防止 buffer 打满阻塞 IO。
- **结果**：PUBACK 仅在 acks=all 确认后返回，实测时延 49ms（可接受），"已 ACK 必不丢"成为平台可靠性承诺；压测工具按已 ACK 口径断言丢消息率=0。
- **反思**：**异步 API 的"返回成功"不等于"持久化成功"**——所有"回执类"协议都要问一句：回执发出去之前，数据真的安全了吗？这条经验后来贯穿到 OTA（进度上报 vs 成功判据分离）、命令 ACK（业务层收敛）的设计里。

### 候选 B：EventLoop 阻塞级联雪崩——"一个慢 Redis 卡死全节点"

- **背景**：Broker 线程模型铁律是 IO 线程零阻塞，但早期实现有几处违规。
- **现象**：Kafka producer `BUFFER_MEMORY=64MB` 打满后 `producer.send()` 默认**阻塞至 max.block.ms=60s**——Kafka 抖动时所有 EventLoop 全部卡死，**整个节点所有连接停摆**（Redis/MySQL/Kafka 抖动直接表现为全节点连接面不可用，自研 Broker 最典型的事故模式）。
- **定位**：代码审查发现 `completeConnect` 在 eventLoop 上执行 `existsSession`/`setString`（阻塞 Redis）、`pushOffline` 三次 Redis RTT 在 IO 线程、retained 写 Redis 在 IO 线程。
- **方案**：① CONNECT 慢路径（认证/连接锁/sessionPresent）全量移入 `brokerExecutor`，回投 eventLoop 仅注册会话；② `pushOffline`/retained 写异步化；③ producer 配 `max.block.ms=200` 快速失败；④ 拒绝策略选"记日志丢弃"而非 `CallerRunsPolicy`（后者让 IO 线程内联执行阻塞任务，等于把坑埋回 EventLoop）。
- **结果**：运行日志无 EventLoop 阻塞告警；故障演练（Redis/MySQL 宕机）验证 fail-closed + 既有会话存活，进程/端口不受影响。
- **反思**：**"线程模型"不是文档，是纪律**——后来加了一个静态防护思路：在 IO 线程入口检测禁止同步阻塞调用（ResourceLeakDetector 式断言 + 单测守护）。

### 候选 C：集群路由架构重构——fan-out 到定向投递（架构级故事）

- **背景**：早期跨节点路由是**全量 fan-out**（每节点消费全部路由 topic），Kafka 出口流量 = (N+1)×总量；单节点 RouterConsumer 单线程串行处理，路由延迟 P99 ≤ 10ms 的目标在节点数增加后不可能达成；信封 JSON+Base64 膨胀 33%。
- **方案（两步走）**：① **上行/路由 topic 分离**：业务上行独立 `mqtt.uplink`（access 唯一消费组，可水平扩展）；② **下行定向化**：按连接锁 owner 解析节点 → 发 `mqtt.down.{nodeId}`（仅目标节点消费）；owner 缺失回落 Redis 离线队列/`mqtt.broadcast`；③ 信封二进制化（magic 0xE9 0x01 + 定长头，decode 自动探测兼容滚动升级）。
- **结果**：Kafka 出口 (N+1)×总量 → ≈1×总量；下行单条消息只进目标节点，规避"无人消费的分区"；`BytesKafkaConsumerEngine` 三通道独立消费组、分区并行、手动提交 + DLQ。
- **反思**：**架构级问题要在容量模型上算账**——"N 节点 × 全量"这种乘数一旦出现，就是扩容的硬天花板，越早重构成本越低。

### 候选 D：通配订阅离线消息丢失（P0-1，小而经典的 bug 故事）

- **背景**：持久会话离线队列补发逻辑用 `session.getSubscriptions().get(m.getTopic())` **具体 topic** 查订阅表，但订阅表 key 是 **topic filter**（如 `pk/dn/down/#`）。
- **现象**：设备用通配符订阅（最常见用法）时永远查不到 → 跳过 → 消息已被 popOffline 从 Redis 删除 → **离线指令彻底丢失**，且 Redis 操作非原子（LRANGE+DEL 两步之间新消息被误删）。
- **方案**：改为遍历订阅 filter 用 `TopicMatcher.matches` 匹配取最高 QoS；离线队列 push/pop 全部 Lua 原子化（RPUSH+LTRIM+EXPIRE / LRANGE+DEL 单脚本）。
- **反思**：**"精确匹配"和"通配匹配"是两种语义**，任何按 topic 找订阅的代码都要先问订阅表存的是 filter 还是字面量。

### Q8-5 如果现在让你重新设计 Broker，你会怎么做？（复盘题）

**一句话**：**连接与会话分离（会话层独立成服务）、按 tenant 限流配额、补齐运维面板与热配置、百万级重新评估自研 vs EMQX**。

**完整回答**：① 会话存储可以考虑独立 session 服务（或直接上成熟方案），降低 Broker 内存压力；② per-tenant/per-device 配额（当前只有 per-device 限流）；③ 运维能力（Dashboard/热配置/滚动升级）是目前与 EMQX 差距最大的一块；④ 如果团队专职人力 < 2 人，百万规模建议 EMQX 集群 + 自研 Broker 退居轻量场景——**把战略评估点说清楚，比嘴硬自研到底更显成熟**。

---

## 9. 开放性 / 防御类问题

### Q9-1 这个项目还有哪些不足？（诚实但可控）

**一句话**：挑 3 个**已知且有对策**的不足，展示复盘能力而不是暴露短板。

**完整回答**（每个都带"下一步"）：
1. **百万级压测未实测**：当前单节点验证 + 架构就绪，100 万连接实测需要分布式压测机（阶段 3 工作，计划 emqtt-bench 16 机）。
2. **运维工具链弱**：无 Broker Dashboard/热配置/滚动升级，对比 EMQX 差距最大（阶段 3 评估引入成熟方案）。
3. **可观测性链路追踪待完善**：已埋 traceId 日志关联 + Prometheus 指标，OTel 全链路追踪延后。
4. **脚本扩展点未启用**：规则引擎预留 Groovy/JS script（Phase 12），当前 TCA 模型覆盖主场景。

### Q9-2 你遇到过最难排查的线上问题？（备选故事）

> 用 Q8 的候选 D（通配订阅丢消息）或候选 A（PUBACK 时序）都可以。加一个通用排查故事模板：**现象（偶发/低概率）→ 排除法（网络/配置/代码）→ 复现（压测/演练）→ 根因（语义错配/时序）→ 修复 + 单测固化 → 沉淀为规范**。

### Q9-3 为什么离开上一家公司 / 职业规划？（9 年经验必问）

> 结合简历（南方智能 2020-2025、网新恩普 2017-2020、河东 2016-2017 实习）：
> - **离职动机**：上家公司（统一 API 开放平台 + 物联网中台）已进入平稳期，核心系统稳定后技术挑战变少；希望去一个**设备规模更大、IoT 技术栈更硬核**的平台（百万级接入、自研接入层），把 9 年积累的分布式/高并发经验用在增量场景。
> - **职业规划**：IoT 平台架构方向——接入层（协议/连接）、消息层（可靠传输）、规则引擎（编排）三个领域持续深挖，3-5 年目标是带 IoT 平台架构团队。
> - **加分点**：提到 smart-doc 开源维护者经历（300+ 企业采用），证明有技术影响力 + 工程规范能力。

### Q9-4 你还有什么想问的？（反问题）

> 面试官问完必反问你。准备 3 个有质量的问题：
> 1. 公司当前设备接入量级和协议栈？（判断岗位技术含量）
> 2. 接入层是自研还是采购 EMQX？（判断团队技术深度与我的价值空间）
> 3. 团队对 Broker/消息链路有没有专职的人？（判断稳定性投入）
> 4. 新人的第一个任务大概率是哪个模块？（判断上手路径）

---

## 附：30 秒数字速记卡（面试前过一遍）

| 项 | 数字 |
|---|---|
| 微服务 | 12+ 个（设计拆分 16 个领域服务） |
| Kafka topic | 15 个，`mqtt.uplink` 24 分区 key=deviceKey |
| 容量目标 | 单节点 25 万连接 / 5 万 msg/s；集群 100 万连接；跨节点路由 P99 ≤ 10ms |
| 实测 | QoS1 上行 PUBACK 49ms；故障恢复 ≤ 30s；控制链路 P99 ≤ 500ms |
| 认证 | HMAC-SHA256 + ±2min 窗口 + nonce 一次性 + 10 次失败封禁 300s |
| Broker 高可用 | 连接锁（TTL 20s + 心跳续期）/ 节点心跳（TTL 30s）/ 会话跟随 / 优雅停机 / 定向回落 |
| 可靠性 | at-least-once + 三层去重（消费组互斥 + 生产幂等 + MessageDedup/业务幂等） |
| 规则引擎 | TCA 模型、5 触发源、4 动作、防抖 300s、嵌套深度 ≤ 5 + 环检测、xxl-job 动态 job |
| OTA | 灰度 1/10/50/100 + 成功率 <95% 自动暂停；重试 2 次/5min；差分 bsdiff + 双 SHA256；成功判据=新版本上报 |
| 单元测试 | 74 个；故障演练 5 个脚本 |

---

*本文所有结论均来自项目源码与设计文档（docs/design/Phase4~12、docs/review/mqttbroker-生产级评估报告.md、docs/decisions/ADR-技术决策记录.md、MQTT-Broker-架构与高可用.md、OTA-面试图解.md），可对照阅读。*
