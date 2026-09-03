# EnergyX 储能管理平台 — Redis Key 规范

> 版本：v1.4（+§3.10 场景规则变更广播通道 + 场景联动 rule:* key）  日期：2026-08-14
> 设计依据：ADR-005（Redis 承担加速 + 过程态，MySQL/TDengine 为权威源）；Phase1 §4.4（Broker 会话共享）

## 1. 命名总则

- **格式**：`{域}:{业务}:{实体key}`，如 `iot:online:{device_id}`、`mqtt:session:{deviceKey}`、`cache:model:{productKey}:{version}`
- **一致性**：所有 key 必须在本文档登记；新增 key 先补文档再编码
- **TTL 强制**：每个 key 声明 TTL；无 TTL 的 key（如影子）必须显式说明理由并受权威源兜底
- **权威源原则**：Redis 中的一切数据要么可从 MySQL/TDengine 重建，要么是过程态（过期无害）；影子/命令记录/凭据在 MySQL 有权威副本

## 2. 命名空间总表

| 域 | Key 示例 | Value 结构 | TTL | 权威源 | 写入方 → 消费方 | 过期/清理 |
| --- | --- | --- | --- | --- | --- | --- |
| 在线状态 | `iot:online:{device_id}` | String(节点ID) | 30s（心跳续期） | 影子(MySQL) | Broker→device 服务 | 心跳续期，超时判离线 |
| 用户会话令牌 | `auth:login_token:{sid}` | String(JSON LoginUser) | 7200s（每请求滑动续期） | sys_user/sys_role/sys_permission | system 认证→JwtAuthenticationTokenFilter | 登出删键；角色/权限变更按会话刷新 |
| 影子 reported | `iot:shadow:reported:{device_id}` | Hash(属性名→值) | 7d | MySQL `iot_shadow` | 消费服务→影子服务 | 删除设备时清理 |
| 影子 desired | `iot:shadow:desired:{device_id}` | Hash(属性名→值) | 7d | MySQL `iot_shadow` | command/strategy→接入 | 同上 |
| 影子 delta | `iot:shadow:delta:{device_id}` | String(JSON) | 30s | MySQL `iot_shadow` | 影子服务→(Kafka iot-shadow-delta) | 设备离线转命令队列 |
| delta 补发冷却 | `iot:shadow:delta:cd:{device_id}` | String(`1`) | `delta-recheck-cooldown-seconds`（默认 30s） | 无（纯限流标记） | 影子服务内部（上报后对账） | 删除设备时随其他影子 key 清理 |
| 命令离线队列 | `iot:cmd:q:{device_id}` | List(JSON指令) | 7d | MySQL `iot_command` | command→接入(上线补发) | 设备上线消费/删除 |
| 命令在途 | `iot:cmd:inflight:{device_id}` | Hash(commandId→timeoutAt) | 5min | MySQL `iot_command` | command→超时扫描 | 超时回收 |
| 命令幂等 | `iot:cmd:idem:{command_id}` | SETNX | 24h | MySQL `iot_command` | command(入口) | 自然过期 |
| 告警持续窗口 | `alarm:sustain:{rule_id}:{device_id}` | String(首违毫秒时间戳) | 窗口+10s | MySQL `iot_alarm_record` | alarm(属性规则) | 值回正常删除 |
| 告警静默 | `alarm:silence:{rule_id}:{device_id}` | SETNX(触发时刻) | 静默期(秒) | MySQL `iot_alarm_record` | alarm | 恢复时删除/自然过期 |
| 影子版本锁 | `lock:shadow:{device_id}` | Redisson 锁 | 租约 | — | shadow 服务 | 版本乐观锁兜底 |
| Broker 会话 | `mqtt:session:{deviceKey}` | Hash(node/cleanSession/ts) | 会话时长 | — | Broker↔Broker | 会话过期 |
| Broker 订阅 | `mqtt:subs:{deviceKey}` | Set(topic@qos) | 会话时长 | — | Broker | 会话过期 |
| Broker inflight | `mqtt:inflight:{deviceKey}` | Hash(packetId→消息+状态JSON) | 会话时长 | — | Broker | QoS 完成即删 |
| Broker 离线队列 | `mqtt:offline:{deviceKey}` | List(JSON消息, 容量上限) | TTL | — | Broker | 上线拉取即清 |
| Broker 连接锁 | `mqtt:conn:{deviceKey}` | String(nodeId) | 会话时长 | — | Broker | 新连接踢旧连接 |
| Broker 保留消息 | `mqtt:retained:{topic}` | String(JSON) | 无 | — | Broker | 新订阅投递，覆盖即删 |
| 认证 nonce | `mqtt:nonce:{nonce}` | SETNX | 5min | — | 接入认证钩子 | 防重放，一次有效 |
| 认证失败计数 | `mqtt:authfail:{clientId}` | INCR 计数 | 10min | — | Broker 认证 | 跨节点共享，窗口内累计 |
| 认证封禁 | `mqtt:ban:{clientId}` | SET | auth-failure-ban-seconds(默认300s) | — | Broker 认证 | 自然过期解封 |
| 限流 | `rl:{scope}:{tenant_id}:{key}` | 计数/滑动窗口 | 窗口期 | — | Gateway/认证钩子 | 自然过期 |
| 分布式锁 | `lock:{resource}` | Redisson 锁 | 租约 | — | 影子/命令/告警 | 看门狗续期 |
| 定时任务锁 | `lock:scheduled:{task}` | String(owner) Lua 原子锁 | TTL=任务最坏耗时 | — | command/alarm/ems/tsdb 定时任务（R-01） | 到期自动释放；防多实例重复执行 |
| 缓存-产品 | `cache:product:{product_key}` | String(JSON) | 10min | MySQL `iot_product` | product→所有读方 | 变更时删除/重建 |
| 缓存-物模型 | `cache:model:{product_key}:{version}` | String(JSON) | 10min | MySQL `iot_thing_model` | product→接入适配 | 版本发布时失效 |
| 缓存-当前物模型 | `cache:model:current:{product_key}` | String(JSON) | 10min | MySQL `iot_thing_model` (is_current=1) | product→access 摄取 | 版本发布时失效 |
| 缓存-设备信息 | `cache:device:{device_key}` | String(JSON) | 30min | MySQL `iot_device` | device→access 摄取 | 设备变更时失效 |
| 缓存-认证凭据 | `cache:cred:{device_key}` | String(JSON) | 30min | MySQL `iot_device_credential` | device→认证钩子 | 凭据变更时失效 |
| 缓存-电站 | `cache:station:{station_id}` | String(JSON) | 5min | MySQL `iot_station` | station→只读方 | 变更失效 |
| 消息去重 | `iot:msg:dedup:{stage}:{device_id}:{message_id}` | SETNX | 300s | — | access/tsdb/shadow/rule 各消费边界 | 自然过期 |
| 场景规则缓存 | `rule:cache:{rule_id}` | String(规则 JSON) | 10min（变更主动删） | MySQL `iot_scene_rule` | rule 服务(热加载) | 规则变更时删除 |
| 场景规则防抖 | `rule:debounce:{rule_id}:{device_id}` | SETNX | debounceSeconds | — | rule 引擎(动作执行前) | 自然过期，恢复动作不受限 |
| 场景规则状态 | `rule:state:{rule_id}:{device_id}` | String(FIRED/RECOVERED) | 随规则生命周期 | — | rule 引擎(边沿触发) | 规则停用/删除时清理 |
| 场景规则定时锁 | `lock:scheduled:rule-{rule_id}` | Redisson/String 锁 | 任务最坏耗时 | — | rule 定时触发 | 到期自动释放，防多实例重复执行 |

## 3. 关键 key 细则

### 3.1 在线状态（`iot:online:*`）

```text
key:   iot:online:{device_id}
value: {broker_node}（String）
TTL:   30s，Broker 每收到心跳/上报续期；离线双通道判定（遗嘱 + 过期）
```
- 在线判定：存在即在线；权威恢复路径：设备上报 → 更新 MySQL `iot_device.last_online_time`
- 风险：TTL 与误判；离线判定默认 30s（可配置），避免网络抖动误报

### 3.2 影子（`iot:shadow:*`）

```text
reported: Hash，字段=物模型属性标识，值=属性值
desired : Hash，字段=属性标识，值=目标值
delta   : String(JSON)，最近一次 desired-reported 差异，驱动设备同步
```
- 双写：热更新 Redis（低延迟读）+ 异步落 MySQL `iot_shadow`（乐观锁版本）
- 读取属性**优先读影子**（ADR-007）：离线也可读最近状态；设备真实值以最新上报收敛

### 3.3 命令（`iot:cmd:*`）

- 下发入口：`SETNX iot:cmd:idem:{command_id}` → 已存在说明重复，幂等返回
- 设备离线：写入 `iot:cmd:q:{device_id}` 队列；设备上线（lifecycle 事件）触发补发
- 设备在线：直接走 Kafka `iot-command-down`；`inflight` 记录超时点，超时扫描驱动重试/置 TIMEOUT

### 3.4 Broker 会话（`mqtt:*`，见 Phase1 §4.4）

- 故障接管：节点宕机 → 设备重连任意节点 → 从 Redis 恢复会话（CleanSession=false）
- 连接锁：`mqtt:conn:{deviceKey}` 保证同 clientId 单连接，新连接踢旧连接（避免指令投递到过期连接）
- **节点心跳租约（2026-08-11 新增）**：`mqtt:node:{nodeId}` = String(nodeId)，TTL 30s，每 10s 刷新。
  节点宕机后心跳 key 30s 消失 → access 下行路由（BrokerNodeResolver）判定 owner 死节点 → 回落广播/离线队列，
  避免把消息发给无人消费的 `mqtt.down.{deadNode}`（原 60s 锁 TTL 悬空窗口缩至 ≤30s）。
  优雅停机时 broker 删除本节点心跳 + SCAN 释放本节点全部连接锁（`mqtt:conn:*` 前缀）。
- **inflight 存储说明（Phase 4 实现细化）**：v1.0 规范为 ZSet(packetId→状态)，但 QoS1/2 断线续传需要携带完整报文（topic/payload），ZSet 成员无法可靠携带二进制。实现定为 **Hash(packetId→JSON 消息+状态)**，状态仍可从 JSON 还原，语义不变。
- **保留消息**：`mqtt:retained:{topic}` 为跨节点权威（节点内存缓存做本节点读），新增 key 于 Phase 4 补登。

### 3.5 消息幂等去重（`iot:msg:dedup:*`，Phase 5 新增）

```text
key:   iot:msg:dedup:{stage}:{device_id}:{message_id}
value: "1"（SETNX）
TTL:   300s（可配置 msg-dedup-ttl-seconds）
```
- **stage 按消费边界隔离**：一条报文会顺序经过多个 Kafka 边界（access 摄入 → tsdb 落库 → shadow/rule 处理），每个边界的 Kafka 交付都可能各自重放。若共用同一 key，上游边界一旦消费过，下游会把合法消息误判为重复而丢弃。故每个边界独立 SETNX、独立 TTL。
- 已登记 stage：`access`（接入摄入 mqtt.router）、`tsdb`（时序落库）；shadow/rule/alarm/ws 边界在 Phase 6 消费方加入。
- 语义：`SETNX` 返回 true=该边界首次处理可继续；false=该边界已处理，幂等跳过。
- 降级：Redis 不可用时去重退化（放行 + 日志），依赖 TDengine 主键覆盖与人工补数兜底。
- **Phase 6 边界刻意不登记**：影子合并/指令 ACK 条件更新/告警静默 SETNX 已天然幂等（见 Phase6 §5.1 幂等矩阵），消息级去重反而会破坏影子重放收敛，故 shadow/command/alarm 消费边界不设 MessageDedup。

### 3.6 用户会话令牌（`auth:login_token:*`，Phase 9 新增）

```text
key:   auth:login_token:{sid}   （sid = JWT 内 sessionId，会话 uuid）
value: LoginUser JSON（含 permissions / roleCodes，每请求从 Redis 反序列化恢复）
TTL:   7200s（与 jwt.expire-seconds 一致），每请求 verifyToken 滑动续期
```
- **B 方案（对齐 RuoYi-Vue TokenService 语义）**：JWT 仅承载身份 claims + `sid`；完整登录态（含权限 Set）序列化存 Redis。`JwtAuthenticationTokenFilter` 每请求 `sid → Redis → SecurityContext`，角色权限变更**实时生效**，登出删除 Redis 键即吊销（JWT 本身仍有效，但会话不存在 → 401）。
- **刷新矩阵**：角色权限变更 `refreshPermissionByRoleCode`；用户角色变更 `refreshUserSessions`；菜单/权限资源写操作 `refreshAllSessions`；禁用/重置密码/删除用户 `revokeUserSessions`（删除该用户全部会话键）。
- **超级管理员**：LoginUser.permissions 含 `*:*:*` 通配，`@ss.hasPermi` 直接放行。
- **风险与治理**：Redis 挂 → 全部会话失效（fail-closed 语义正确）；管理操作 `keys auth:login_token:*` 为 O(N)，仅低频管理操作触发可接受，生产可改 SCAN 游标。

### 3.7 物模型/设备信息缓存（Phase 5 新增）

- `cache:model:current:{product_key}`：当前生效物模型（`iot_thing_model.is_current=1`），access 摄取时查询 + **L1 进程内 ConcurrentHashMap**（max 1000 清空）两级缓存；
- `cache:device:{device_key}`：设备信息（device_id/station/enterprise/product_key），access 摄取时把 topic 的 `{pk}/{dn}` 解析为内部 ID 用；
- 两级缓存结构：L1 内存（热点，无网络）→ L2 Redis → MySQL 兜底，MySQL 不可用不回源、降级用缓存值。

### 3.8 Broker 集群路由 topic 约定（阶段 2 新增，替代 mqtt.router fan-out）

```text
mqtt.uplink             设备上行（deviceKey key，24 分区）：Broker 唯一生产者；
                        唯一消费组 energy-access-uplink 摄取，Broker 自身不再 fan-out
                        （Topic ACL 保证设备只订自己 down/*，跨设备订阅不存在）
mqtt.down.{nodeId}      下行定向（24 分区）：access/跨节点 Broker 写，仅目标节点消费
                        （消费组 mqtt-down-{nodeId}），投递目标 = mqtt:conn:{deviceKey} 的 owner
mqtt.broadcast          跨节点广播（8 分区）：KICK 踢线、owner 解析失败/离线回落；
                        每节点唯一消费组 mqtt-bc-{nodeId} 全量 fan-out + sourceNode 去重
mqtt.router（兼容期）    旧 fan-out 通道：阶段 2 默认停用（router-legacy-broadcast=false），
                        仅多版本混布升级期开启；下线后删除
```

- 信封编码：`mqtt.uplink/down/broadcast` 使用二进制信封（RouterEnvelopeCodec，magic=0xE9 0x01），
  替代 JSON+Base64（payload 零膨胀、免序列化）；`mqtt.router` 兼容期保持 JSON。
- 节点解析：`mqtt:conn:{deviceKey}` = String(nodeId)，同时承担「连接锁」与「下行路由定位」
  两个职责；TTL 60s 随心跳续期，owner 缺失（离线/竞态）时下行回落 mqtt.broadcast。
- 消费组约定：`mqtt-down-{nodeId}`（定向）、`mqtt-bc-{nodeId}`（广播）、`energy-access-uplink`（上行唯一组）。

### 3.9 凭据失效广播（P2-6 新增，pub/sub 通道）

```text
channel: mqtt:cred:revoked
消息体: {clientId}（String）
发布方: 设备服务（吊销/禁用/重置凭据时）
订阅方: Broker CredentialRevokeSubscriber → 删除 cache:cred:{clientId} + 踢在线连接强制重认证
```

- 目的：凭据吊销从「cache:cred TTL 30min 后生效」缩到秒级；离线设备下次重连自动回源 MySQL 拿到最新状态。
- 通道归属 `mqtt:` 域（Broker 连接面），与 Redis-key 规范统一管理。

### 3.10 场景规则变更广播（Phase 11 新增，pub/sub 通道）

```text
channel: rule:changed
消息体: {ruleId} 或 ALL（String）
发布方: energy-rule（规则增删改/启停时）
订阅方: energy-rule 其他实例 → 增量刷新本地规则缓存（rule:cache:* 删除 + 进程内索引重建）
```

- 目的：规则热更新从「cache:rule TTL 10min 后生效」缩到秒级，多实例规则引擎配置一致收敛。
- 通道归属 `rule:` 域（规则引擎配置面），与 Redis-key 规范统一管理。

## 4. 缓存一致性策略

| 场景 | 策略 |
| --- | --- |
| 产品/物模型变更 | 主动删除 `cache:product:*` / `cache:model:current:*` / `cache:model:*:*`，下次读重建（Cache Aside） |
| 设备信息变更 | 主动删除 `cache:device:*`，下次读重建；设备删除需连同 `iot:online` 一并清理 |
| 影子 reported 更新 | 先 Redis 热更新 → 异步批处理落 MySQL；崩溃窗口内以 Redis 为准，可重建 |
| 认证凭据变更 | 删除 `cache:cred:*` + 主动踢线（强制重认证） |
| 命令幂等 | SETNX + 24h TTL，超时后允许同 commandId 重入（业务幂等由 commandId 语义保证） |
| 消息去重 | SETNX + 300s TTL，超窗后同 (stage, deviceId, messageId) 允许重入（Kafka 重放窗口 5min 内） |

## 5. 运维约定

- **Cluster 部署**：3 主 3 从起步；`hash tag` 仅在同分片事务场景使用（如 `{device_id}` 包裹命令队列 key）
- **热 key 治理**：高频设备影子按 `device_id` 天然打散；产品/物模型缓存加本地进程缓存（Caffeine）二级兜底
- **大 key 监控**：`iot:cmd:q:*` 离线队列设容量上限（如 500 条），超限丢弃最旧并告警
- **降级**：Redis 不可用时，影子/在线态回退 MySQL/TSDB 查询（读降级），写入进 Kafka 缓冲
