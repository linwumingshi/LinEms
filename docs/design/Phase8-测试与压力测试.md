# EnergyX 储能管理平台 — Phase 8 · 测试与压力测试

> 版本：v1.0    日期：2026-08-06
> 前置：Phase 1~7 已完成（架构/库表/后端/接入/消息/业务/前端）
> 范围：设备 SDK（8a）/ 压测工具（8b）/ 故障演练（8c）/ 文档与构建验证（8d）
> 目标指标：1M 设备、20~50 万 msg/s 上行、5M points/s、控制 P99 ≤ 500ms、可用性 99.95%

## 1. 设计说明

### 1.1 交付物总览

| 交付 | 位置 | 说明 |
| --- | --- | --- |
| 设备 SDK | `sdk/java/` | Netty MQTT 客户端：HMAC 认证、属性/事件/生命周期上行、下行指令自动 ack、keepalive、**指数退避自动重连**；25 个 socket 级单测全绿 |
| 压测工具 | `test/stress/` | 单 fat-jar：`seed`（设备造数）/ `connect`（建连速率与延迟分位）/ `throughput`（上行吞吐）/ `control`（控制链路 P99）；`java -jar stress.jar` 直接运行 |
| 故障演练 | `test/drill/` | 5 个黑盒演练脚本 + 公共库 + 总入口 + 手册，覆盖 Kafka 重平衡 / Broker 重启 / Redis 降级 / MySQL 切换 / 控制 P99 |
| 本文档 | `docs/design/Phase8-测试与压力测试.md` | 设计说明与验收 |

### 1.2 分层验证策略

```
单元/集成    压测验证      故障验证
────────     ────────     ────────
SDK 认证契约  seed 造数    01 Kafka 重平衡
SDK 报文契约  connect 建连  02 Broker 重启自愈
SDK 重连行为  throughput 吞吐 03 Redis 降级
Broker 统计   control P99  04 MySQL 切换
                           05 控制 P99 基线
```

- **SDK 单测**把「认证密码即 HMAC 十六进制签名、username 三段式」钉死在字节级契约上，压测与演练的产线验证都复用同一套认证实现。
- **压测工具**与演练脚本共用 `stress.jar`，保证「造数 → 建连 → 压测 → 演练」的设备集完全一致（productKey= snd_ess_pcs、secretBase 派生密钥）。
- **演练脚本**全部经 Broker HTTP 统计（`8082 /internal/broker/stats`）做黑盒断言，不侵入代码。

### 1.3 演练覆盖的故障语义

| 故障 | 预期行为 | 代码依据 |
| --- | --- | --- |
| Redis 宕机 | 新连接 deny(3)（fail-closed），既有内存会话存续，Broker 进程/端口存活 | `MqttChannelInboundHandler` 对 `authenticate()` 异常统一 `AuthResult.deny(3,"认证服务异常")` |
| MySQL 停服 | 认证查库失败 → 同样 deny(3)；既有会话存续 | `DeviceAuthService` 凭据查询抛异常被 handler 兜住 |
| Broker 崩溃 | 设备 SDK 指数退避自动重连（1s×2ⁿ 上限 30s），连接数 120s 内自愈 | SDK `MqttDevice.scheduleReconnect` |
| 消费端重启 | Kafka 协调器重平衡，分区重分配，LAG 追平无丢失 | `energy-access-uplink` 等 10 个消费组按 deviceId 分区保序 |

## 2. 技术决策理由

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | SDK 用 Netty codec-mqtt 4.1.111 而非 Paho | 与自研 Broker 同族编解码，报文契约零翻译误差；Paho 不在本地 Maven 缓存，避免联网拉取 |
| D2 | 认证契约以**字节级单测**固化：password 字段 = HMAC-SHA256 十六进制字符串的 UTF-8 字节 | 早期发现测试 Broker 双重 hex 编码的坑后，改为与生产 Broker 完全一致的 `new String(passwordInBytes(), UTF_8)` 解码并比对签名串，杜绝「SDK 对、Broker 错」或反之的隐性偏差 |
| D3 | SDK 自动重连放**独立单线程调度器** | 重连的阻塞 connect 不能占住共享 NioEventLoopGroup（压测多设备共用 2~16 线程），退避调度与 IO 线程解耦 |
| D4 | 压测工具自研（Percentiles/ConnectUtil/Seeds）而非 JMeter 插件 | 对接私有 HMAC 认证与私有 Topic 契约需要定制 client；fat-jar + CLI 便于演练脚本直接调用 |
| D5 | 造数、压测、演练共用 `stress.jar` | 参数一致避免「测试用的设备集和产线不一致」；`seed` 用 `INSERT IGNORE` 幂等，可反复执行 |
| D6 | 演练用**黑盒统计**（Broker 8082 stats）而非抓日志 | 统计口径与压测/演练三方一致；Broker 宕机时统计接口不可达本身就是一条断言 |
| D7 | 演练脚本全部先查前置条件、不满足即 `die` | 保证脚本在任何环境下的行为可预期；全栈未起时给出明确指引而非空转 |
| D8 | MySQL 切换演练默认**预演模式**，`--execute` 才真实停服 + trap 兜底恢复 | 停库是不可逆动作，trap 保证中断/失败时自动 `net start` 恢复，默认不碰生产服务 |

## 3. 项目目录结构

```text
sdk/java/                                # 8a 设备 SDK（独立 Maven 工程）
├── pom.xml                              # netty-codec-mqtt 4.1.111 / jackson 2.15.4 / logback
└── src
    ├── main/java/com/sanduo/device/
    │   ├── HmacAuth.java                # HMAC-SHA256 签名 / username 三段 / nonce / 密钥生成
    │   ├── DeviceIdentity.java          # productKey+deviceName → clientId / topic 推导 + 校验
    │   ├── CommandMessage.java          # 下行指令 DTO（fromMap 工厂）
    │   ├── DeviceListener.java          # onConnected / onCommand / onDisconnected / onError
    │   ├── MqttClientConfig.java        # 连接配置（含 autoReconnect 退避参数）
    │   └── MqttDevice.java              # Netty 客户端：认证/上行/下行 ack/keepalive/自动重连
    └── test/java/com/sanduo/device/
        ├── HmacAuthTest.java            # 9 用例（含 openssl 向量）
        ├── DeviceIdentityTest.java      # 4 用例
        ├── MqttTestBroker.java          # 最小 MQTT Broker（踢线/reject/silence 能力）
        └── MqttDeviceWireTest.java      # 12 用例（socket 级 wire 契约 + 重连）

test/stress/                            # 8b 压测工具（独立 Maven 工程，依赖 SDK）
├── pom.xml                              # shade 插件 → target/stress.jar
└── src/main/java/com/sanduo/stress/
    ├── StressCli.java                   # 子命令入口 seed/connect/throughput/control
    ├── SeedDevices.java                 # JDBC 批量 INSERT IGNORE 造数（es_device 库）
    ├── ConnectLoad.java                 # 建连速率/延迟分位/保持连接
    ├── ThroughputLoad.java              # 按设备分片的上行吞吐（确定性无重叠）
    ├── ControlLatency.java              # 下发→ACK→SUCCESS 全链路 P99
    ├── PlatformClient.java              # 网关 REST 客户端（POST/GET 指令）
    ├── ConnectUtil.java / Percentiles.java / Secrets.java
    └── target/stress.jar                # fat-jar（已构建，10MB）

test/drill/                             # 8c 故障演练
├── lib.sh                               # 公共库：探测/统计/进程/压测/消费组助手
├── 01-kafka-rebalance.sh                # 消费组重平衡 + LAG 追平
├── 02-broker-restart.sh                 # Broker 重启 + SDK 自动重连自愈
├── 03-redis-degrade.sh                  # Redis 宕机 fail-closed + 会话存续
├── 04-mysql-failover.sh                 # MySQL 停服切换（--execute 才真停，trap 兜底）
├── 05-command-p99.sh                    # 控制链路 P99 ≤ 500ms 回归基线
├── run-all.sh                           # 顺序编排 01→05
├── README.md                            # 演练手册（前置/清单/判定语义）
└── logs/                                # 运行日志
```

## 4. 核心代码

### 4.1 SDK 认证签名（`HmacAuth.java`）

```java
public static String sign(String deviceSecret, String clientId, String timestamp, String nonce) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(deviceSecret.getBytes(UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal((clientId + "&" + timestamp + "&" + nonce).getBytes(UTF_8)));
}
// username = clientId&ts&nonce；CONNECT password 字段 = 上述 64 位十六进制签名的 UTF-8 字节
// 单测用 openssl 向量钉死：secret="test-secret-0123456789" → "50eabec9…f94d6"
```

### 4.2 SDK 自动重连（`MqttDevice.java`）

```java
// channelInactive（且非主动 close）：通知监听器后进入指数退避
if (wasConnected && !closed) {
    listener.onDisconnected(identity, "channel-inactive");
    scheduleReconnect(0);
}
// 独立单线程调度器执行阻塞 connect，避免占用共享 EventLoopGroup
long delay = Math.min((long) config.reconnectBackoffMs() * (1L << Math.min(attempt, 5)),
        config.reconnectMaxBackoffMs());
reconnectScheduler.schedule(() -> doReconnect(attempt), delay, MILLISECONDS);
// doReconnect 失败 → scheduleReconnect(attempt+1)：1s→2s→4s→8s→16s→30s(封顶)
```

### 4.3 压测工具：确定性分片（`ThroughputLoad.java`）

```java
// 按 worker 预划分片，杜绝并发抢号重叠与尾部设备丢失
int lo = (int) ((long) w * connected / workers);
int hi = (w == workers - 1) ? connected : (int) ((long) (w + 1) * connected / workers);
for (int i = lo; i < hi; i++) { /* 该 worker 只发布自己分片内的设备 */ }
```

### 4.4 演练公共库（`lib.sh`）

```bash
broker_connections() { # 经 8082 /internal/broker/stats 取 connections 字段
  curl -sf -m 3 "http://127.0.0.1:${BROKER_HTTP_PORT}/internal/broker/stats" \
    | sed -n 's/.*"connections":\([0-9][0-9]*\).*/\1/p'
}
kafka_group_lag_sum() { # 动态定位 LAG 列并对齐列号求和（无消费者时为全量积压）
  col=$(echo "$header" | tr ' ' '\n' | grep -nx 'LAG' | head -1 | cut -d: -f1)
  kafka_group_describe "$1" | tail -n +2 | awk -v c="$col" 'NF>=c {s+=$c} END {print s+0}'
}
```

### 4.5 演练 02 关键流程（Broker 重启自愈）

```bash
stress connect --count 100 --hold-seconds 600 &   # 后台保持 100 条真实连接
wait_connections 100 90                            # 基线：统计连接数达标
stop_service energy-mqtt-broker                    # kill → 端口 1883 关闭
start_service energy-mqtt-broker                   # nohup java -jar 重启
wait_connections 100 120                           # SDK 退避重连 → 连接数自愈
# PASS = 120s 内连接数恢复 100/100（无人工干预）
```

## 5. 测试方案

### 5.1 SDK 单元/集成测试（25 用例全绿，`mvn clean test`）

| 文件 | 覆盖点 | 用例 |
| --- | --- | --- |
| `HmacAuthTest` | 签名与 openssl 向量一致、username 三段拼接、nonce 格式、密钥长度 | 9 |
| `DeviceIdentityTest` | clientId/topic 推导与 TopicAcl 一致、非法字符校验 | 4 |
| `MqttDeviceWireTest` | 认证密码字节级契约、属性/事件/生命周期 topic+payload、下行指令自动 ack 与关闭、keepalive PINGREQ、优雅断开 DISCONNECT、拒绝/超时/已关闭分支、**踢线自动重连**与关闭重连 | 12 |

> 验证记录：`Tests run: 25, Failures: 0, Errors: 0`，`BUILD SUCCESS`。
> 两个重连用例用 `connections().size() >= 2` 作为「第二次 CONNECT 已到 Broker」的确定性信号（记录在回 CONNACK 前追加，无异步竞态）。

### 5.2 压测工具自检

```bash
java -jar test/stress/target/stress.jar help   # 子命令与参数说明（已验证输出）
```

### 5.3 故障演练

```bash
cd test/drill
./run-all.sh            # 01→05 顺序执行（04 为预演模式）
./run-all.sh --execute  # 04 真实停 MySQL（trap 兜底恢复）
./03-redis-degrade.sh   # 单独执行
```

| 演练 | 断言 | 判定基准 |
| --- | --- | --- |
| 01 | 消费组在册、分区全归属；突发后 LAG≤100；重启 energy-access 后重平衡、LAG 再归零 | 全程无 FAIL |
| 02 | 100 连接基线 → Broker 重启 → 120s 内恢复 100/100 → 再开 50 成功 | 连接数达标 |
| 03 | 停 Redis：新连接全部拒绝、既有会话 >0、Broker 存活；恢复后新连接成功 | fail-closed + 隔离 + 恢复 |
| 04 | 停 MySQL：同上；恢复后新连接成功 | 同 03（--execute 才真停） |
| 05 | 控制链路 P99 ≤ 500ms（回归基线） | P99 判定 |

> **当前环境说明**：脚本已通过 `bash -n` 语法校验并在前置缺失时给出明确退出码；
> 端到端实跑需先拉起全栈（Nacos/Kafka/Redis/MySQL/Broker/接入/指令/网关），步骤见
> `test/drill/README.md` 前置条件表。压测工具 `seed/connect/throughput/control` 四子命令
> 均已构建并在 `help` 下自检通过。

### 5.4 压测命令速查（全栈就绪后）

```bash
java -jar test/stress/target/stress.jar seed       --count 100000 --product snd_ess_pcs
java -jar test/stress/target/stress.jar connect    --count 100000 --concurrency 500 --hold-seconds 60
java -jar test/stress/target/stress.jar throughput --count 10000 --rate 20 --duration 60
java -jar test/stress/target/stress.jar control    --count 200 --concurrency 50
```

## 6. 下一阶段任务（生产化加固）

1. **Broker 多节点验证**：两节点集群 + `mqtt.router` 跨节点路由，实测水平扩容下 20~50 万 msg/s 吞吐与连接接管（演练 02 的节点级版本）；
2. **百万连接实测**：`connect --count 1000000` 配合资源监控（句柄/内存/GC），校准单机 `max-connections=500000` 与横向扩展策略；
3. **TSDB 写入压测**：TDengine 在 5M points/s 下的稳定写入与保留策略验证；
4. **前端压测数据回归**：大数据量下驾驶舱 ECharts 渲染与告警推送稳定性；
5. **混沌扩展**：Kafka 单节点宕机、Nacos 不可用、TDengine 降级回放（见演练手册「扩展演练」）。

## 7. 本阶段验收自评

- ✅ SDK 完整可运行：HMAC 认证契约字节级固化、上下行报文契约、自动重连、25 单测全绿
- ✅ 压测工具：seed/connect/throughput/control 四子命令 + 确定性分片，fat-jar 构建成功
- ✅ 故障演练：5 脚本 + 公共库 + 总入口 + 手册，`bash -n` 全过，前置缺失时干净退出
- ✅ 演练覆盖四大故障语义：Kafka 重平衡、Broker 自愈、Redis/MySQL fail-closed、控制 P99 基线
- ✅ 无伪代码/空方法/TODO；异常路径（超时/拒绝/服务不可用）均有明确失败信息
- ⏳ 端到端实跑待全栈拉起（当前环境仅 Redis/MySQL 在线）；操作步骤已写入演练手册
