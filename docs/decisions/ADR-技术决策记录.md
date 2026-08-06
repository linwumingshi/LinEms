# ADR — 架构决策记录

> 状态说明：Accepted（已接受）/ Proposed（提议中）/ Superseded（已被替代）。
> 每条 ADR 记录：背景 → 决策 → 理由 → 代价 → 备选方案。后续阶段如需变更，在此追加而非改写历史。

---

## ADR-001 自研 Netty MQTT Broker 而非引入 EMQX

- **状态**：Accepted（2026-08-06）
- **背景**：接入层需承载百万级 TCP 长连接，支持 MQTT 3.1.1/5.0；需要设备认证、QoS 状态机、离线消息、集群会话接管。
- **决策**：自研 Netty MQTT Broker 集群（节点内 epoll + 内存热会话；集群会话共享落 Redis；跨节点消息路由走 Kafka `mqtt.router` 分区）。
- **理由**：
  1. 协议栈、Session、QoS、集群协调全链路可控，是平台最核心的技术差异化与面试亮点；
  2. 储能场景可深度定制：控制类指令 QoS/ACK 语义、物模型 topic 路由、设备级 ACL 全部内建；
  3. 无商业依赖与版税成本，与"百万设备接入平台"能力主张一致。
- **代价**：工程量最大；需自研处理连接容量、会话共享、跨节点路由、故障接管等分布式难题（Phase 4 实现）。
- **备选**：EMQX 集群（成熟但黑盒）；混合模式（EMQX 生产 + 自研模拟器）。

---

## ADR-002 时序数据库选用 TDengine

- **状态**：Accepted（2026-08-06）
- **背景**：电压/电流/SOC/温度/功率等秒级采样，峰值 500 万 points/s，查询按"设备 × 指标 × 时间窗"为主。
- **决策**：TDengine 作为唯一时序存储，原始数据 + 多级降采样表分层保留。
- **理由**：标签+时间线（STABLE）模型天然契合按设备/指标查询；SQL 兼容降低使用成本；写入吞吐极高且资源占用低；内置降采样/保留策略，省去单独聚合链路；国内储能行业事实标准。
- **代价**：与 MySQL 分属两套引擎，跨库 JOIN 需在应用层聚合（由 energy-station 实时聚合层承担）。
- **备选**：InfluxDB（高基数短板 + 集群商业授权）；TimescaleDB（超大规模写入聚合性能不足）。

---

## ADR-003 Kafka 作为全链路消息总线

- **状态**：Accepted（2026-08-06）
- **背景**：设备上行 20万~50万 msg/s，需削峰、解耦、多消费组扇出、指令下行保序。
- **决策**：Kafka 为唯一消息主干；所有跨服务异步交互以 topic 事件表达。
- **理由**：高吞吐、分区内有序（deviceId/topic 为 key）、可重放、多消费组天然扇出、生态完善；at-least-once + 下游幂等兜底语义简单可靠。
- **代价**：不提供事务消息的强一致性；跨分区无全局顺序（本项目不需要）。
- **备选**：RocketMQ（事务消息但吞吐/扩展略逊）；Pulsar（架构先进但组件多、运维重）。

---

## ADR-004 事件驱动微服务拆分

- **状态**：Accepted（2026-08-06）
- **背景**：平台横跨设备接入、资产管理、策略、告警、AI 多领域，需要独立扩缩容与团队边界。
- **决策**：按领域拆分为 16 个微服务（见 Phase 1 §5），服务间同步走 Feign（读），异步走 Kafka 事件（写/通知）。
- **理由**：各服务独立发布/扩容/故障隔离；设备接入与业务解耦后 Broker 可独立压测；领域边界清晰，符合 DDD 实践。
- **代价**：分布式事务成本转嫁为最终一致 + 幂等补偿；需全链路可观测支撑。
- **备选**：模块化单体（前期快但百万级扩展受限）。

---

## ADR-005 Redis 的职责定位

- **状态**：Accepted（2026-08-06）
- **背景**：在线状态、设备影子、指令队列、Broker 会话、限流、分布式锁都需要低延迟存储。
- **决策**：Redis Cluster 承担"加速 + 过程态"，一切可重建/可落库的数据都以 MySQL/TDengine 为权威源，Redis 仅做缓存与队列。
- **理由**：Redis 非持久化不可靠，把影子、命令记录、认证凭据的持久化放在 MySQL，Redis 只存热数据与过程态（session/inflight/offline/TTL），避免宕机丢数据。
- **代价**：影子持久化需写双份（Redis 快 + MySQL 落盘）。

---

## ADR-006 MySQL 分库分表策略

- **状态**：Accepted（2026-08-06）
- **背景**：百万设备 × 生命周期/命令/告警记录，单库单表不可承受。
- **决策**：ShardingSphere 分库分表 + 读写分离。分库按 tenant_id/enterprise_id；device/command 按 device_id hash 分表；告警/审计按月分表归档。
- **理由**：储能场景写入集中在设备数据与命令，按 device 维度分片保证单设备读写落同一分片，避免跨分片事务。
- **代价**：跨分片聚合受限（由报表/ES 兜底）；需规划好 sharding key，后期调整成本高。
- **备选**：TiDB（分布式事务完整，但引入独立技术栈与运维复杂度）。

---

## ADR-007 设备影子模式（reported / desired）

- **状态**：Accepted（2026-08-06）
- **背景**：设备离线时配置（如充电功率 50kW）需持久化并在上线后同步。
- **决策**：影子服务维护 desired/reported 双文档 + 版本号；reported 来自设备上报，desired 来自平台配置；delta 事件驱动设备同步；读取属性优先读影子（快速、离线可用），离线下发通过影子延迟同步。
- **理由**：与 AWS IoT Shadow 一致的心智模型，解决离线控制与状态一致性；版本号乐观锁防并发覆盖。
- **代价**：影子与真实设备状态存在短暂不一致窗口（由 delta 推送收敛）。

---

## ADR-008 物模型规范（对齐阿里云 IoT 物模型）

- **状态**：Accepted（2026-08-06）
- **背景**：设备属性（SOC/电压/电流/温度/功率）、服务（启停充放/调功率/设模式）、事件（过温/过压/BMS 故障）需要统一建模与动态校验。
- **决策**：产品维物模型，属性/服务/事件以 JSON Schema 定义存储于 MySQL（energy-product），运行时由接入适配服务按 Schema 动态解析与校验，数据落 TDengine 时按属性标识打点。
- **理由**：新设备类型免发版即可接入；Schema 可演进版本化；与主流物联网平台心智对齐，便于演示与面试讲解。
- **代价**：动态 Schema 校验有 CPU 成本（Schema 缓存 + 校验器池化）。

---

## ADR-009 控制指令走 QoS1 + 业务幂等（Command Center）

- **状态**：Accepted（2026-08-06）
- **背景**：充电/放电控制类指令不允许乱序与重复执行。
- **决策**：指令下发链路 commandId 全局唯一，设备按 commandId 幂等；指令按 deviceId 分区保证串行；Command Center 维护完整状态机（CREATED→SENT→DEVICE_RECEIVED→EXECUTING→SUCCESS/FAILED/TIMEOUT），超时重试 + 上限熔断。
- **理由**：MQTT QoS2 仅保证报文恰好一次，不保证业务不重放；控制语义必须在业务层以 commandId 收敛，QoS1 足够且吞吐更好。
- **代价**：设备端需实现 commandId 去重与 ACK 规范（SDK 内置）。

---

## ADR-010 用户认证选 Spring Security + Redis 会话令牌（B 方案）

- **状态**：Accepted（2026-08-06）
- **背景**：P0-1 网关验签落地后，需补齐 RBAC 权限点校验与用户/角色/菜单/单位四大基础管理模块；技术栈选型面向后续第三方登录（Spring OAuth2）。
- **决策**：认证栈选 **Spring Security**（`@EnableMethodSecurity` + `@Service("ss")` PermissionService 提供 `@ss.hasPermi` 权限点，对齐若依 sys_menu 语义）。会话令牌采用 **B 方案 = RuoYi-Vue TokenService 模式**：JWT（jjwt 0.12.6，HS 系列）仅承载身份 claims + 会话 uuid `sid`，完整 `LoginUser`（含权限 Set）序列化至 Redis `auth:login_token:{sid}`；`JwtAuthenticationTokenFilter` 每请求 `sid → Redis → SecurityContext`；登出删除 Redis 键即吊销；角色/权限变更实时刷新在线会话。
- **理由**：① 权限/账号变更**实时生效**（禁用、改角色立即踢下线），无需等 token 过期；② 借鉴若依成熟方案、风险低，网关 `GlobalAuthFilter` 保持 P0-1 原样不改；③ 共享 `@ss.hasPermi` 层，后续接 OAuth2 仅替换 token 基础设施，业务权限点零改动。
- **代价**：每请求一次 Redis 读；管理操作 `keys auth:login_token:*` 为 O(N)；Redis 不可用时全部会话失效（fail-closed 语义正确）。
- **备选（否决）**：Plan A 纯 JWT——权限内嵌 token，权限变更需黑名单/等过期，实时性差，故弃。

---

## 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.0 | 2026-08-06 | 初版 9 条 ADR |
| v1.1 | 2026-08-06 | 新增 ADR-010（Spring Security RBAC + Redis 会话令牌 B 方案） |
