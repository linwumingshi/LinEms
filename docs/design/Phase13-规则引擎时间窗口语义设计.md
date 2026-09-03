# Phase13 · 规则引擎时间窗口语义设计

> 状态：设计评审中（未编码）
> 创建：2026-09-02
> 影响模块：`energy-rule`(8116)、`energy-alarm`(8115)
> 关联文档：`Phase11-场景联动与规则编排设计.md`、`Redis-key规范.md`

---

## 1. 背景与目标

### 1.1 问题

当前场景联动的触发条件是**单条消息瞬时判定**：一条遥测进来，Trigger 匹配 + Condition 匹配，成立即执行动作。缺少任何时间维度的判定能力，导致：

- 瞬时尖峰（采样毛刺、网络重传、启动浪涌）直接触发告警，误报率高；
- 无法表达「超温持续 5 分钟才算异常」「10 分钟内掉线 3 次才算故障」这类真实运维语义；
- `debounce_seconds` 只能压制**动作重复执行**，不能压制**误判本身**——它是在错误已经发生后不重复通知，而不是让错误不发生。

### 1.2 目标

在 `energy-rule` 建立**统一的窗口语义内核**，覆盖四种时间判定语义，并把 `energy-alarm` 侧已有的、语义错误的持续窗口迁移过来复用。

**非目标**（明确不做，见 §9）：不引入 Flink / Kafka Streams / eKuiper；不做窗口聚合与变化率；不做告警升级与收敛。

---

## 2. 业界对标结论

### 2.1 事实标准：TCA 模型

阿里云场景联动、华为云 IoTDA 云端规则、腾讯云 IoT Explorer、ThingsBoard、AWS IoT Events 全部采用 **Trigger / Condition / Action** 模型。本项目 `iot_scene_rule` 的 `trigger_json` / `condition_json` / `action_json` 三列已对齐该模型，无需调整骨架。

### 2.2 时间窗口语义的业界命名

| 语义 | 业界命名 | 出处 |
|---|---|---|
| 持续 N 秒才告警 | `Duration` / 持续时间 | ThingsBoard Alarm Rule Condition Type；华为云「设备状态触发」Duration（0–60 分钟） |
| 窗口内 N 次才告警 | `Repeating` / 重复次数 | ThingsBoard；AWS IoT Events detector model 用 `setVariable` 计数器实现 |
| 命中后延时 N 秒执行 | 延时执行 | 阿里云场景联动高级选项（0~86400 秒） |
| 连续 N 次才告警 | `Repeating` 的连续变体 | ThingsBoard（中间断一次即清零） |

### 2.3 两个容易被忽略、但本项目需要的业界能力

1. **数据时效**（华为云）：设备 19:00 产生数据、平台 20:00 收到，超过设定时效则**不触发**。用于拦截离线补传与网络抖动导致的误告警。
2. **报警规则中间状态持久化**（ThingsBoard）：官方原文 *"Without persistence, a 10-minute duration alarm resets to 0 on restart."* 对应本项目当前窗口状态全在 Redis 且无重建机制的隐患。

### 2.4 关于 Flink 的决策：**不引入**

**核心事实：没有任何一家主流 IoT 平台的规则引擎用 Flink 实现。** ThingsBoard 用 Akka actor + 可选 DB 状态；AWS IoT Events 用 detector model 状态机；阿里云与华为云均为自研。Flink 在 IoT 的落点是**流处理管道**（EMQX → Kafka → Flink 做窗口聚合/CEP），不是规则引擎。

**阻抗点**：规则引擎要求「用户随时改规则、秒级生效」，而 Flink 作业是**编译提交态**。要做动态规则需走 Flink CEP 动态模式或 Stateful Function，工程与运维成本会吃掉全部收益。

**量化依据**：10 万设备 × 每设备 5 条启用窗口规则 × 窗口内 60 点 × 约 16B ≈ **480MB**。Redis 可承受。需要引入流引擎的量级是设备数百万 + 规则数万 + 窗口点数数百，与当前规模差两个数量级。

**架构姿态**：把窗口评估抽象为 `WindowEvaluator` 接口，本期实现 `RedisWindowEvaluator`。未来若确需 Flink / eKuiper，新增一个实现即可，业务代码不动。这使「是否上 Flink」成为**可推迟的决策**，而非现在要下的架构赌注。

---

## 3. 现状与差距（代码级）

### 3.1 现有评估链路

`RuleEngine.processRule`（`backend/energy-rule/src/main/java/com/energyx/rule/engine/RuleEngine.java:139`）主流程：

```
triggered(OR) → conditionEvaluator(AND) → recovery 边沿 → debounceGuard → actionExecutor → writeLog
```

入口消费者：`PropertyRuleConsumer` / `LifecycleRuleConsumer` / `AlarmRuleConsumer`，均先过 `MessageDedup.tryOnce("rule", deviceId, messageId, 300)`。

### 3.2 差距清单

| 项 | 现状 | 证据 |
|---|---|---|
| 窗口字段 | **完全不存在**。`RuleTrigger` 仅 `type/device/property/op/value/cron/event/alarmCode/level/state` | `model/RuleTrigger.java:26-71` |
| 规则级时间字段 | 仅 `debounce_seconds`（默认 300） | `sql/mysql/85_rule.sql:26` |
| 触发/条件类型 | `PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL`；条件 `DEVICE_STATUS/TIME_RANGE/PROPERTY` | `engine/DslValidator.java:29-33` |
| 状态结构 | `rule:state:{ruleId}:{deviceId}` 为单值 String（FIRED/RECOVERED），无计数、无时间序列 | `util/RuleRedisKeys.java:22` |
| 告警侧窗口 | `AlarmCondition.windowSec` 存在，但 `AlarmService.isSustained` **语义错误** | `AlarmService.java:315-337` |

### 3.3 三个存量缺陷

**D1 · `isSustained` 语义错误（真 bug）**

`AlarmService.isSustained` 只记录**首次违反时刻**，中途值回落**不重置**（`resetSustain` 仅在恢复路径调用）。因此它不是「持续满足」，而是「首次违反后累计时长」——温度冲高一次后回落，5 分钟后仍会误告警。

**D2 · `RecoveryTracker` 非原子（并发竞态）**

`RecoveryTracker.shouldFire`（`engine/RecoveryTracker.java:40-57`）是 get-then-set，无 Lua、无 SETNX。`energy-rule` 是 Kafka 消费组多实例，rebalance 时同一设备的消息可能被不同实例并发处理，导致状态覆盖、动作重复执行。

**D3 · 物模型事件触发缺失（新功能，非缺陷）**

> **更正**：前序调研曾判断 `RuleTrigger.event` 是断头字段，此判断**错误**。grep 确认 `event` 归 LIFECYCLE 使用（`DslValidator.java:81` 校验其必须为 `ONLINE/OFFLINE`），并非死字段。
>
> 真实情况是：**「物模型事件上报」作为一种触发源尚不存在**。且由于 `event` 字段已被 LIFECYCLE 占用，新增该触发源**必须另起字段名**（如 `eventCode`），不能复用 `event`。此项列为 P2，不在本期范围。

---

## 4. 设计

### 4.1 总体思路

四种窗口语义本质上是**同一个「命中时间戳序列」上的四个不同判定函数**。因此不写四段逻辑，而是：

1. 统一维护一个命中时间戳序列（ZSET）或一个首次命中时刻（String），二者取一；
2. 一次 Lua 完成「裁剪 → 写入 → 计数 → 判定 → 续 TTL」；
3. 五种语义只是 Lua 内的分支判定与「未命中时是否清空」的差异。

**关键区分**：`DURATION` 与 `CONSECUTIVE` 在「未命中」时**清空状态**（连续性语义）；`REPEATING` 在「未命中」时**只裁剪不清空**（累计语义）。这一条差异就是各语义的核心实现差别。

**`DURATION` 必须单独用 String key，不能复用 ZSET。** 原因：若用 ZSET 判 `now - min(S) >= W`，当连续时长刚好达到 `windowSec` 时，最早那个 hit 的 score 已小于 `now - W`，会被 `ZREMRANGEBYSCORE` 裁掉，`min(S)` 后移，**判定永远不成立，规则永不触发**。这是本设计自检中发现的陷阱，必须用独立 String key 存首次命中时刻规避。

### 4.2 数据模型扩展（JSON DSL，无需 DDL）

`trigger_json` 与 `recovery_json` 为 MySQL `JSON` 列，**新增字段不改表结构**，`dsl_version` 保持 1（向后兼容，缺省字段走默认值）。

**`RuleTrigger` 新增字段**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `windowType` | String | `SIMPLE` | `SIMPLE` / `DURATION` / `REPEATING` / `CONSECUTIVE` / `DELAY` |
| `windowSec` | Integer | null | 窗口时长（秒），`DURATION`/`REPEATING`/`CONSECUTIVE`/`DELAY` 必填，1–86400 |
| `windowCount` | Integer | null | 窗口内命中次数阈值，`REPEATING`/`CONSECUTIVE` 必填，>=1 |
| `cancelOnRecover` | Boolean | true | 仅 `DELAY`：延时期间条件消失是否取消执行 |

**`RuleRecovery` 新增字段**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `mode` | String | `IMMEDIATE` | `IMMEDIATE` / `CONSECUTIVE` / `DURATION` |
| `count` | Integer | null | `CONSECUTIVE` 模式：连续 N 次正常才恢复 |
| `durationSec` | Integer | null | `DURATION` 模式：持续正常 N 秒才恢复 |

**`DslValidator` 校验矩阵**（新增 `validateWindow(t)`）

| windowType | windowSec | windowCount | 备注 |
|---|---|---|---|
| SIMPLE | 必须 null | 必须 null | 现有行为 |
| DURATION | 必填 1–86400 | 必须 null | |
| REPEATING | 必填 1–86400 | 必填 >=1 | |
| CONSECUTIVE | 必填 1–86400 | 必填 >=1 | 强制声明窗口，防止状态无限膨胀 |
| DELAY | 必填 1–86400 | 必须 null | |

### 4.3 窗口语义与存储结构

设 `S` 为 ZSET 中的命中序列，`F` 为 String key 中的首次命中时刻，`now` 为服务端当前毫秒，`W = windowSec * 1000`，`N = windowCount`。

| 语义 | 存储 | 命中时 | 未命中时 | 判定条件 |
|---|---|---|---|---|
| `SIMPLE` | 无 | 不走窗口 | 不走窗口 | 瞬时成立即触发（现状） |
| `DURATION` | String `F` | `SETNX F now`（幂等，已存在则不改） | **`DEL F`** | `now - F >= W` |
| `CONSECUTIVE` | ZSET `S` | `ZADD S now` | **`DEL S`** | `\|S\| >= N`（未命中即清零，故 `\|S\|` 即连续计数） |
| `REPEATING` | ZSET `S` | `ZADD S now` | 仅裁剪，不清空 | `\|S\| >= N`（裁剪移出窗口的命中，语义正确） |
| `DELAY` | String `F` | `SETNX F now`，并登记到期索引 | 依 `cancelOnRecover` 决定是否 `DEL` | 到期扫描到 `now >= F + W` 且条件仍成立 → 触发 |

> `DURATION` 与 `DELAY` 在多数序列上判定结果相同，但语义不同：`DURATION` 要求**连续满足**；`DELAY` 只要求**命中后等一段时间**，`cancelOnRecover=false` 时中途条件消失仍会执行。
>
> `CONSECUTIVE` / `REPEATING` 的裁剪是安全的：裁剪只会减少 `\|S\|`，使判定更严格（等价于「这 N 次必须都落在窗口内」），符合预期。但需校验 `N <= maxPoints`，否则 `maxPoints` 截断会导致永远达不到 `N`（见 §4.4）。

### 4.4 Redis key 与 Lua 脚本

**新增 key**（按项目铁律，先补 `docs/design/Redis-key规范.md` 再编码，唯一出口 `RuleRedisKeys`）

| Key | 类型 | 用途 | TTL |
|---|---|---|---|
| `rule:win:z:{ruleId}:{deviceId}` | ZSET | 触发窗口命中序列（`REPEATING` / `CONSECUTIVE`），score=epochMillis，member=messageId | `windowSec * 2 + 60` |
| `rule:win:dur:{ruleId}:{deviceId}` | String | 触发窗口首次命中时刻（`DURATION`），O(1) | `windowSec * 2 + 60` |
| `rule:win:rec:{ruleId}:{deviceId}` | ZSET | 恢复窗口序列（`CONSECUTIVE`，喂入 `!conditionsMet`） | 同上 |
| `rule:win:recdur:{ruleId}:{deviceId}` | String | 恢复窗口首次正常时刻（`DURATION`） | 同上 |
| `rule:delay:{ruleId}:{deviceId}` | String | `DELAY` 首次命中时刻（到期时刻 = 该值 + windowSec） | `windowSec + 300` |
| `rule:delay:index` | ZSET | `DELAY` 到期索引，score=到期时间戳，member=`{ruleId}:{deviceId}` | 无（条目按需删除） |
| `rule:win:snapshot:{ruleId}:{deviceId}` | String | 可选 DB 快照（默认关） | `windowSec * 4` |

> `rule:delay:index` 用 ZSET 而非 `SCAN rule:delay:*`，到期扫描复杂度从 O(全库 key) 降为 O(log N)。

**Lua：`rule_window_eval.lua`（一次调用完成全部逻辑）**

入参：`KEYS[1]=ZSET key`、`KEYS[2]=String key`；`ARGV = [satisfied(0/1), mode, now, windowSec, count, maxPoints, ttlSec, member]`
mode 编码：`2=DURATION`、`3=REPEATING`、`4=CONSECUTIVE`、`5=DELAY`（`1=SIMPLE` 不调用本脚本）

出参：`{matched, size, firstHitAt, elapsedMs}`

```lua
local satisfied, mode, now = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local windowSec, count, maxPoints, ttlSec = tonumber(ARGV[4]), tonumber(ARGV[5]),
                                            tonumber(ARGV[6]), tonumber(ARGV[7])
local W = windowSec * 1000
local ttlMs = ttlSec * 1000

-- ===== DURATION / DELAY：String key 存首次命中时刻（O(1)，规避 ZSET 裁剪陷阱）=====
if mode == 2 or mode == 5 then
  if satisfied == 0 then
    if mode == 2 then
      redis.call('DEL', KEYS[2])                       -- DURATION：中断即清零
      return {0, 0, 0, 0}
    end
    return {0, 0, 0, 0}                                -- DELAY：取消动作由应用层按 cancelOnRecover 处理
  end
  redis.call('SET', KEYS[2], now, 'NX', 'PX', ttlMs)   -- 幂等：重复投递不刷新首次时刻
  local f = tonumber(redis.call('GET', KEYS[2]))
  local elapsed = now - f
  redis.call('PEXPIRE', KEYS[2], ttlMs)
  if mode == 5 then
    return {0, 1, f, elapsed}                          -- DELAY 恒返回 pending，由 §4.7 调度器落地
  end
  return {elapsed >= W and 1 or 0, 1, f, elapsed}
end

-- ===== REPEATING / CONSECUTIVE：ZSET 命中序列 =====
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - W)   -- 裁剪窗口外命中
if satisfied == 0 then
  if mode == 4 then
    redis.call('DEL', KEYS[1])                             -- CONSECUTIVE：中断即清零
    return {0, 0, 0, 0}
  end
  local m = redis.call('ZCARD', KEYS[1])                   -- REPEATING：只裁剪、不清零
  return {m >= count and 1 or 0, m, 0, 0}
end

redis.call('ZADD', KEYS[1], now, ARGV[8])                  -- member=messageId，重复投递幂等
local n = redis.call('ZCARD', KEYS[1])
if n > maxPoints then                                      -- 上限保护，防高频上报打爆 Redis
  redis.call('ZREMRANGEBYRANK', KEYS[1], 0, n - maxPoints - 1)
  n = redis.call('ZCARD', KEYS[1])
end
local first = 0
if n > 0 then
  first = tonumber(redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')[2])
end
redis.call('PEXPIRE', KEYS[1], ttlMs)
local matched = 0
if n >= count then
  matched = 1
end
return {matched, n, first, now - first}
```

**四个关键性质**

1. **天然幂等**：`DURATION` / `DELAY` 走 `SET NX`，重复投递不刷新首次命中时刻；`REPEATING` / `CONSECUTIVE` 以 `messageId` 为 member，`ZADD` 覆盖相同 member 与相同 score，不会重复计数。`messageId` 已由 `MessageDedup` 在链路入口产出，可直接复用。
2. **原子性**：裁剪、写入、计数、判定、续 TTL 在单次 Lua 内完成，天然消除 D2 的 get-then-set 竞态。
3. **内存有界**：ZSET 路径由 `maxPoints` 截断；`DURATION` / `DELAY` 是 O(1) 单值。`rule.window.max-points-per-window` 默认 200。
4. **校验约束**：`DslValidator` 必须校验 `windowCount <= rule.window.max-points-per-window`，否则 `maxPoints` 截断会导致 `|S|` 永远达不到 `N`，规则永不触发。

### 4.5 引擎链路改造

`RuleEngine.processRule` 新顺序（`RuleEngine.java:139` 起）：

```
triggered(OR)
  → conditionsMet(AND)
  → windowEvaluator.evaluate(spec, ruleId, deviceId, conditionsMet)   ← 新增
  → recoveryTracker（改用窗口输出，而非瞬时 conditionsMet）
  → debounceGuard
  → actionExecutor
  → writeLog（新增 reason 区分）
```

**关键决策：窗口喂入的是 `conditionsMet` 而非 `triggered`。**

理由：语义是「规则完整成立的次数」。若只统计 Trigger 命中，则「温度 > 80 **且** 时间在 8:00–18:00」这类规则会把 18:00 之外的点也计入窗口。喂 `conditionsMet` 让时间范围、设备状态、跨设备条件自然生效。

**恢复判定改造**：现有 `handleRecovery` 用瞬时 `conditionsMet` 做单次下降沿判定。改为 `conditionsMet=false` 时喂入恢复窗口（`rule:win:rec:*`），按 `RuleRecovery.mode` 判定后才真正恢复。

### 4.6 时间基准与数据时效

**默认使用服务端处理时间（processing time）**，不使用设备事件时间。

理由：遥测链路为 设备 → broker → Kafka → rule，设备时钟漂移与网络延迟不可控，用事件时间会导致窗口判定错乱。

配套**迟到丢弃**兜底（顺带实现华为云「数据时效」）：

- 配置项 `rule.window.max-late-seconds`，默认 300；
- 若 `now - eventTime > maxLateSec`，该条消息**不参与窗口累计**，记 `reason=LATE_DROPPED`；
- 效果：拦截离线补传与网络抖动引发的批量误告警。

### 4.7 DELAY 的到期调度

- 命中时 Lua 以 `SET NX` 写 `rule:delay:{ruleId}:{deviceId}` = **首次命中时刻**（不是到期时刻；到期时刻 = 首次命中 + windowSec，由扫描时计算）。同一次 Lua 内 `ZADD rule:delay:index` 登记 `{ruleId}:{deviceId}`，score = 首次命中 + windowSec；
- 未命中且 `cancelOnRecover=true` 时删除预约 key 与索引条目；
- 复用项目已有 **xxl-job** 基础设施（`job/TimerJobManager.java`、`XxlJobAdminClient.java`），新增每分钟任务：
  - 抢分布式锁 `lock:scheduled:rule-delay-scan`（沿用现有锁约定）；
  - `ZRANGEBYSCORE rule:delay:index 0 now` 取到期项；
  - 逐项回读规则缓存，二次确认条件仍成立，成立则执行动作；
  - 删除预约与索引条目。
- **精度限制**：分钟级扫描，`DELAY` 最小粒度 60 秒。`DslValidator` 对 `windowSec < 60` 的 DELAY 规则给出明确校验提示。
- P2 若需秒级精度，改用 Redisson `RDelayedQueue`（`redisson-spring-boot-starter` 已在依赖中）。

### 4.8 可选 DB 快照

对齐 ThingsBoard 的 `Persist state of alarm rules`，解决「重启后 10 分钟持续告警归零」：

- 配置项 `rule.window.snapshot-enabled`，**默认 false**；
- 开启后，仅对 `windowSec >= rule.window.snapshot-min-seconds`（默认 600）的规则，在窗口**首次命中**与**状态跃迁**时把 `{firstHitAt, count}` 写入 `rule:win:snapshot:*` 并异步落 MySQL；
- 进程启动或缓存未命中时回读快照重建。
- 默认关闭的原因：仅为长窗口规则付费，避免每条遥测一次 DB 写造成写放大。

### 4.9 存量缺陷修复

| 编号 | 修复内容 |
|---|---|
| D1 | `AlarmService.isSustained` 改为委托 `WindowEvaluator`（`DURATION` 模式），中途回落即重置；废弃 `resetSustain` 的现有语义 |
| D2 | `RecoveryTracker.shouldFire` / `shouldRecover` 改为**单条 Lua 原子 compare-and-set**，消除多实例竞态。注意 `GETSET` 自 Redis 6.2 起已废弃，改用 `SET key value GET`（或直接并入 Lua 脚本），不得沿用废弃命令 |
| D3 | 不在本期。`event` 字段已被 LIFECYCLE 占用，物模型事件触发需新增 `eventCode` 字段，列 P2 |

---

## 5. 兼容性

- **存量规则零影响**：`windowType` 缺省为 `SIMPLE`，行为与现状完全一致。
- **JSON 向后兼容**：新增字段可选，`dsl_version` 保持 1，旧 JSON 反序列化不报错。
- **Redis key 无冲突**：新增 key 均在 `rule:win:*` / `rule:delay:*` 命名空间，与现有 `rule:cache:*` / `rule:debounce:*` / `rule:state:*` 无交集。
- **告警侧行为变更**：D1 修复后，`windowSec` 的语义从「首次违反后累计」变为「连续满足」。存量使用 `windowSec` 的告警规则**触发时机会变晚**（这是修复目的），需在上线说明中标注。

---

## 6. 可观测性

- Micrometer 指标（模块已覆盖 micrometer 1.13.4 + prometheus 1.2.1）：
  - `rule_window_eval_total{result=matched|pending|reset|late_dropped}`
  - `rule_window_lua_latency`（Timer，p50/p95/p99）
  - `rule_window_zset_size`（Gauge，观测内存）
- 执行日志：现有 `iot_scene_exec_log` **每次评估都 insert 一条**（含未命中与防抖拦截，`RuleEngine.writeLog:317-338`）。引入窗口后评估频次不变但命中路径更长，写放大加剧。
  - 新增配置 `rule.log.verbose-enabled`（**默认 false**）：关闭时仅落「触发 / 恢复 / 失败」三类，未命中与窗口 pending 只累加计数器不落库。

---

## 7. 测试计划

### 7.1 单元测试

`RedisWindowEvaluatorTest`（连接宿主机 Redis 6379，非 Docker 容器）

- 四种语义各自：边界值（恰好等于 `windowSec` / `count`）、差一错误、超时裁剪；
- `DURATION` / `CONSECUTIVE` 的**中断重置**行为（中途一条不满足必须归零）；
- `REPEATING` 的**不清零**行为（与上面形成对照）；
- **`DURATION` 裁剪陷阱回归**：连续命中时长超过 `windowSec` 后仍必须触发（若误用 ZSET 存序列，`min(S)` 会被裁剪后移导致永不触发）；
- 幂等性：同一 `messageId` 重复投递，计数不重复增长、首次命中时刻不被刷新；
- `maxPoints` 截断生效；校验 `windowCount > maxPoints` 的规则被拒；
- 迟到消息被丢弃且不污染窗口。

### 7.2 存量缺陷回归

- D1：温度冲高一次后回落，持续窗口内**不得**触发（修复前会误触发）；
- D2：并发 100 线程同一 `ruleId:deviceId` 调 `shouldFire`，断言恰好一个返回 true。

### 7.3 端到端验证（实证核查，接受标准）

用 `sim-device` 造数据，跑完后**实地查 Redis / MySQL 状态**确认，不接受口头结论：

1. Kafka `iot-thing-property` 消费无积压、无重复触发；
2. `rule:win:*` 的 ZSET 大小与 TTL 符合预期，无残留孤儿 key；
3. `iot_scene_exec_log` 中触发/恢复记录数量与预期一致；
4. 规则停用/删除后，`rule:win:*`、`rule:debounce:*`、`rule:state:*` 全部清理；
5. 重启 `energy-rule` 进程，长窗口规则在 `snapshot-enabled=true` 下状态不归零。

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| Redis 内存增长 | 高频上报 + 长窗口导致 ZSET 膨胀 | `maxPoints` 默认 200 硬截断；`rule_window_zset_size` 指标监控 |
| Lua 脚本阻塞 Redis | 脚本复杂度 O(窗口点数)，长窗口下变慢 | `maxPoints` 限规模；p99 延迟告警；必要时改增量计数 |
| 告警侧行为变更 | D1 修复后存量规则触发时机会变晚 | 上线说明标注；灰度先开新规则 |
| DELAY 精度 | 分钟级扫描，最小粒度 60s | 校验期提示；P2 用 Redisson 延迟队列 |
| 前端配置界面 | DSL 新增字段后前端表单需同步 | **本次只做后端**，前端配置表单另开一轮（见 §9） |

---

## 9. 本期不做（明确范围）

- **不引入** Flink / Kafka Streams / eKuiper（决策见 §2.4）；
- 不做 `AGGREGATE`（窗口聚合）、`RATE`（变化率）、`DEADBAND`（死区迟滞）；
- 不做告警升级、告警收敛、频次限制、站点级聚合（属 S4 子系统）；
- 不做物模型事件触发源（D3，需 `eventCode` 新字段）；
- 不做本地内存窗口缓存（P2 优化：本地 L1 + Redis L2）；
- **不做前端配置界面**——本期仅后端 + DTO 校验；前端表单改造需单独确认范围。

---

## 10. 实施顺序

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | 补 `Redis-key规范.md`；修 D2（`RecoveryTracker` Lua 原子化） | 无 |
| P1 | `RuleWindowType` + DSL 字段扩展 + `DslValidator` 校验矩阵 | 无 |
| P2 | `RedisWindowEvaluator` + Lua 脚本 + 单元测试 | P0、P1 |
| P3 | `RuleEngine` 链路接入 + 数据时效（迟到丢弃） | P2 |
| P4 | 修 D1（alarm 侧委托窗口内核） | P2 |
| P5 | 恢复窗口（`RuleRecovery.mode`） | P3 |
| P6 | DELAY 到期调度器（xxl-job + 索引 ZSET） | P2 |
| P7 | 可选 DB 快照 + 可观测性指标 + 端到端实证核查 | P3–P6 |

> P0–P4 是核心价值闭环（四种语义中的三种 + 存量缺陷清理），建议作为一个可交付批次；P5–P7 作为第二批次。
