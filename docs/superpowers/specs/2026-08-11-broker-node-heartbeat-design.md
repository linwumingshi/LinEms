# Broker 下行路由避让：节点心跳租约（消除死节点 60s 悬空窗口）

> 日期：2026-08-11 ｜ 状态：待实施（v2，实验后修正落点）
> v1 前提（"接管窗口 60s"）经实测**证伪**：设备重连路径本为立即接管（kickRemote + overwriteConnLock，实测 1194ms），
> 不依赖锁 TTL 过期。v2 将优化落点修正为**下行路由避让死节点**。

---

## 一、实验发现（推翻 v1 前提）

| 项 | v1 假设 | 实测 |
|---|---|---|
| 设备重连接管 | 需等锁 TTL 60s 过期 | **立即接管 1194ms**（`MqttChannelInboundHandler:459-465`：tryAcquire 失败 → kickRemote + overwriteConnLock） |
| "55s 接管窗口" | 真实系统行为 | **测试脚本假象**——脚本 kill 后主动等锁过期才发起重连 |

**真实问题链**：节点宕机 → 设备连接仍指向死节点（锁 owner=死节点，TTL 60s）→ command 服务查 owner 定向 `mqtt.down.broker-2`（无人消费）→ **下行悬空最长 60s**，直到锁过期转广播/离线队列，或设备重连后 overwrite 接管。

**结论**：锁 TTL 不是接管瓶颈（设备重连即接管）；**瓶颈是 command 下行路由在 60s 内持续把消息发给死节点**。

## 二、方案（v2 修正）

### 核心：节点心跳 + 下行路由避让

1. **节点心跳 key**：`mqtt:node:{nodeId}`，TTL `node-heartbeat-ttl-seconds: 30`
2. **心跳刷新**：broker 侧新增 `NodeHeartbeatScheduler`，每 `node-heartbeat-interval-seconds: 10` 刷新（复用 brokerScheduler）
3. **下行避让**（核心改动）：`BrokerNodeResolver.resolveNode`（access 侧）查 owner 后，**再查该 owner 的心跳 key**：
   - owner 心跳存在 → 返回 owner（正常定向）
   - owner 心跳不存在（判定死节点）→ 返回 null（回落广播/离线队列，不等 60s）
4. **broker 侧兜底**：`tryAcquireConnLock` 失败分支，若 owner 心跳已死 → 跳过 kickRemote 直接 overwrite（省一次无效广播）
5. **生命周期**：broker 启动注册心跳；`@PreDestroy` 删除心跳 + 批量释放本节点连接锁

### 辅助：锁 TTL 收窄

- `conn-lock-ttl-seconds: 60 → 20`：心跳判定失效的极端兜底（60s → 20s）

### 预期效果

| 场景 | 下行悬空窗口 |
|---|---|
| 正常宕机（心跳 key 30s 消失） | **≤30s**（原 60s） |
| 心跳 key 也异常 | ≤20s（锁 TTL 兜底） |

## 三、改动清单

| 文件 | 改动 | 侧 |
|---|---|---|
| `BrokerProperties.java` | 加 nodeHeartbeatIntervalSeconds=10 / nodeHeartbeatTtlSeconds=30；connLockTtlSeconds 60→20 | broker |
| `BrokerKeys.java` | 加 nodeHeartbeat(nodeId)、connLockPrefix() | broker |
| 新建 `NodeHeartbeatScheduler.java` | @Scheduled 10s 刷新心跳；启动注册 + @PreDestroy 清理 | broker |
| `SessionStore.java` | tryAcquireConnLock 失败分支：owner 心跳死 → overwrite（跳过 kick）；新增 releaseAllConnLocks(nodeId) | broker |
| `MqttChannelInboundHandler.java` | 接管分支调 SessionStore 新判定（若已接管跳过 kickRemote） | broker |
| `AccessKeys.java` | 加 nodeHeartbeat(nodeId) | access |
| `BrokerNodeResolver.java` | resolveNode：owner 心跳死 → null（回落广播/离线） | access |
| `application.yml` | 追加心跳配置项 | broker |
| `docs/design/Redis-key规范.md` | 登记 mqtt:node:{nodeId} | doc |

## 四、边界与风险

- **正常路径无影响**：心跳 key 只在"owner 判定"时被读，在线设备不受影响
- **误判风险**：节点 GC/网络瞬时 30s 无心跳 → 另一节点判定死亡 → 下行回落广播（正确兜底）而非丢失；设备重连/锁续期恢复后定向恢复
- **Redis 写放大**：每节点 10s 一次 SET，N 节点 = N×6 次/分，可忽略
- **access 无心跳 key 兼容**：心跳判定失败/异常一律返回 owner（保守定向），不因新功能阻断既有路由

## 五、验证

1. 单测：BrokerNodeResolver 心跳死判定；SessionStore 接管分支
2. 三节点实测：复用 failover 场景，kill 节点后**不重连设备**，验证 command 侧 resolveNode 在 ≤30s 内返回 null（回落）
3. 回归：broker 70 单测 + access 单测全绿；多节点脚本（除 failover 的等待逻辑外）全绿
