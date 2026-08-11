# Broker 锁接管窗口优化：节点心跳租约（60s → ~20-30s）

> 日期：2026-08-11 ｜ 状态：待实施
> 依据：三节点故障演练实测——kill 节点后连接锁 TTL 60s 自然过期才被接管（实测 55s）；
> 期间下行定向（mqtt.down.{nodeId}）悬空。用户确认按推荐方案（路线 A 节点心跳为主 + B 锁 TTL 收窄为辅）改造。

---

## 一、现状与问题

**连接锁机制**（`SessionStore.java`）：
- `mqtt:conn:{deviceKey}` 存 owner nodeId，TTL `conn-lock-ttl-seconds: 60`
- 设备心跳时 `LifecycleNotifier.renewOnline` 调 `refreshConnLockIfOwner` 续期（在线 TTL 30s + 锁 TTL 60s）
- 节点宕机 → 锁无续期 → **60s 自然过期后新节点才能 overwrite 接管**

**问题**：接管窗口最长 60s（实测 55s），期间 command 服务按 owner 定向 `mqtt.down.broker-2` 无人消费，下行悬空。

## 二、方案（用户已确认）

### 核心：节点级心跳租约（路线 A）

1. **节点心跳 key**：`mqtt:node:{nodeId}`，TTL `node-heartbeat-ttl-seconds: 30`
2. **心跳刷新**：新 `NodeHeartbeatScheduler` 每 `node-heartbeat-interval-seconds: 10` 刷新一次（复用 `brokerScheduler`）
3. **接管判定**：`SessionStore.tryAcquireConnLock` 失败分支（锁已存在）→ 读 owner → 查 `mqtt:node:{owner}` 不存在 → **判定旧节点死亡 → `overwriteConnLock` 立即接管**
4. **生命周期**：启动时注册心跳 key；`@PreDestroy` 优雅停机删除本节点心跳 key + 批量释放本节点持有的连接锁

### 辅助：锁 TTL 收窄（路线 B）

- `conn-lock-ttl-seconds: 60 → 20`（兜底窗口 60s → 20s；续期频率不变，Redis 写压力不增）

### 预期效果

| 场景 | 接管窗口 |
|---|---|
| 正常宕机（kill） | ~30s（心跳 TTL） |
| 极端（心跳 key 也未写入） | ~20s（锁 TTL 兜底） |

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `BrokerProperties.java` | 加 `nodeHeartbeatIntervalSeconds=10`、`nodeHeartbeatTtlSeconds=30`；`connLockTtlSeconds 60→20` |
| `BrokerKeys.java` | 加 `nodeHeartbeat(nodeId)` → `mqtt:node:{nodeId}` |
| `SessionStore.java` | `tryAcquireConnLock` 失败分支加"owner 心跳死亡则 overwrite"判定；新增 `releaseAllConnLocksIfOwner(nodeId)`（停机批量释放，用 SCAN 按前缀） |
| 新建 `NodeHeartbeatScheduler.java` | `@Scheduled` 每 10s 刷新心跳；启动注册 + `@PreDestroy` 清理 |
| `application.yml` | 追加 `node-heartbeat-interval-seconds` / `node-heartbeat-ttl-seconds` |
| `docs/design/Redis-key规范.md` | 登记 `mqtt:node:{nodeId}` |

## 四、边界与风险

- **正常路径无影响**：心跳 key 只在"抢占失败后判定旧节点死活"时被读，不影响在线设备连接/续期
- **单节点兼容**：默认配置下（node-id=broker-1）心跳照常跑，行为等价
- **误判风险**：若节点因 GC/网络瞬时不可达导致心跳 key 过期（30s 无刷新），另一节点可能误接管——但锁本身有 20s TTL + 设备重连后新节点建会话、旧节点连接仍在时 KICK 广播会踢掉旧连接，最终收敛
- **多节点写放大**：心跳 10s 一次/节点，N 节点 = N×6 次/分钟 Redis SET，可忽略

## 五、验证

1. 单测：`tryAcquireConnLock` 死亡接管分支（mock Redis：锁存在+owner 心跳 key 缺失 → overwrite）
2. 三节点实测：复用 `mqtt-failover-test.js`，kill 后**不清锁**，断言接管时间 < 35s（原 55s）
3. 回归：70 单测全绿 + 已有 5 个多节点脚本全绿
