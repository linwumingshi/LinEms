# P0-5 首轮真实压测与 P99 基线 — 设计

- 日期：2026-08-06
- 状态：已获用户批准（2026-08-06）
- 对应缺陷：C-01「容量声明无实测支撑（1M 设备 / 20-50万 msg/s / 500万 points/s）」、C-02「控制链路 P99≤500ms 无基线」、C-03「演练 01-05 从未端到端执行」
- 验收口径（Phase9 §5 P0-5）：本机起单机 Kafka/MySQL/TDengine，跑通 `stress throughput` 与演练 05，产出首组吞吐与 P99 基线 | 拿到实测峰值 + 瓶颈分析报告；P99 与当前实现对齐

## 1. 目标边界（用户已确认）

1. **只建基线 + 瓶颈分析报告，不修瓶颈**：本次发现的一切瓶颈/差距（含 G-1/G-2，见 §2）只记录进报告，不改代码。
2. **压测规模 = 分级加压找峰值**：2000 台×20 msg/s = 4万 msg/s 起步，4万→8万→16万→32万逐档翻倍，直到吞吐不再线性增长或资源打满；每档 60s。
3. **演练范围 = 05 + 本机可行的故障演练**：05（控制链路 P99）、01（Kafka 重平衡）、02（broker 重启自愈）；03（需 docker-redis）/04（需管理员停 MySQL）在报告中如实标注受限未执行。

## 2. 环境侦察结论（探测实证）

**平台当前已就绪**：原生 Nacos 3.1.0（8848，group ENERGY，11/11 服务健康）、原生 Redis（6379）、原生 MySQL（3306）、Docker Kafka 4.1.2（9092，KRaft 单节点）、Docker TDengine 3.3.1.0（6030/6041）、Broker 管理端口 8082。`test/stress/target/stress.jar` 已构建存在。

**三项新实证（本次探测确认）**：

| # | 实证 | 结论 |
|---|---|---|
| E1 | Broker 统计端点 | 真实路径为 `GET /internal/broker/stats`（管理端口 8082，`BrokerStatsController`），返回 `connections / subscriptions / messagesIn / messagesOut / messagesRoutedCrossNode / authFailures / acceptedConnections / rejectedConnections / nodeId / mqttPort / maxConnections / uptimeMillis`。此前探测 `/stats` `/stat` `/metrics` 均业务 404（40400）。**这是设备→Broker 接受率的直接观测源**。 |
| E2 | **G-1 差距：tsdb 从不执行 DDL 建库** | `sql/tdengine/00_database.sql` 头注释声称「由 energy-tsdb 服务启动时执行（TDengine 无 Flyway）」，但 energy-tsdb 源码**零 DDL 加载逻辑**（无 ApplicationRunner / @PostConstruct / classpath 资源引用），`iot_tsdb_raw / iot_tsdb_agg / iot_tsdb_event` 三库实测全部不存在。→ tsdb 落库路径当前必失败，压测前必须手动/脚本建库。 |
| E3 | **G-2 差距：提交 DDL 与 TDengine 3.3.1 单节点不兼容** | 原样执行 `00_database.sql` 报语法错：`DAYS 10` 应为 `DURATION 10`；`FSYNC 0` 应为 `WAL_FSYNC_PERIOD 0`；`REPLICA 2` 单节点建库不可用（须 `REPLICA 1`）。`VGROUPS 32` 单节点过大，本机降为 8。**提交的 DDL 需修正才能在 3.3.1 单节点跑通**（脚本内置修正版，原文件不改）。 |

**写入路径已手动端到端验证**：修正版 DDL 建库（`iot_tsdb_raw PRECISION 'ms' KEEP 365 DURATION 10 BUFFER 256 WAL_LEVEL 2 WAL_FSYNC_PERIOD 0 REPLICA 1 VGROUPS 8`）→ 建 STABLE `st_prop_snd_ess_pcs`（列 ts/msg_id/data_type/soc/voltage/current/power/temp/run_mode + TAGS device_id/station_id/enterprise_id/product_key）→ `INSERT INTO dev_{deviceId} USING st_prop_snd_ess_pcs TAGS (...)` → 读回全通。列与 `TdengineSqlBuilder` 及 `ThroughputLoad` 发布字段（soc/voltage/current/power/temp/runMode）完全对齐。

**外部观测点全部可用**：
- Broker：`curl http://127.0.0.1:8082/internal/broker/stats`（返回 JSON `data.messagesIn` 等）。
- Kafka LAG：`docker exec ems-kafka /opt/kafka/bin/kafka-consumer-groups.sh --describe --all-groups`（apache/kafka 官方镜像脚本路径），12 个消费组（energy-access-uplink / energy-tsdb-prop / energy-cmd-ack / mqtt-router-broker-1 等）。
- TDengine：`curl -u root:taosdata -d 'SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs' http://127.0.0.1:6041/rest/sql`（REST 为 raw SQL body，非 JSON 包裹）。

**stress CLI 参数**（`StressCli` usage 实证）：`throughput --count N --rate 20 --duration 60`；`control --count 200 --concurrency 50 --gateway http://127.0.0.1:8000`；`seed --count N --product snd_ess_pcs --secret-base sanduo-stress`。`throughput` 打印 avg/P50/P95/P99 msg/s + failures；`control` 打印 P50/P95/P99/P999/max + PASS/FAIL（P99≤500ms）。

## 3. 方案（用户已确认：方案 A）

**复用现有工具零改动**（`stress` 子命令 + 演练脚本）+ **新增一个编排脚本 `run-baseline.sh`**。不新增第三方依赖、不改任何后端/前端代码、不改 `GlobalAuthFilter`。

## 4. 架构与组件

### 4.1 编排脚本 `test/stress/run-baseline.sh`（NEW）

Git Bash，`set -euo pipefail`，仓库根执行。伪代码结构：

```bash
#!/usr/bin/env bash
# P0-5 首轮真实压测 + P99 基线编排
# 前置：全栈已启动（Nacos/Kafka/Redis/MySQL/TDengine + 11 服务）；MYSQL_PASSWORD 已导出
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STRESS_JAR="$ROOT/test/stress/target/stress.jar"
RESULTS_DIR="$ROOT/test/stress/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

# ── 0. 前置检查（fail-fast）───────────────────────────────
# Nacos 8848 / Kafka 9092 / Broker 8082 / TDengine 6041 / MySQL 3306 端口探活
# Broker /internal/broker/stats 拉基准值（记录 messagesIn 起点）

# ── 1. TDengine 初始化（幂等）─────────────────────────────
# SHOW DATABASES 缺 iot_tsdb_raw → 用修正版单节点 DDL 建库 + 建 STABLE
#   st_prop_snd_ess_pcs（属性宽表，列对齐 TdengineSqlBuilder）
#   st_event（事件表，列对齐 EventTsdbConsumer）
# 差异（DURATION/WAL_FSYNC_PERIOD/REPLICA 1/VGROUPS 8）记入报告 §G-2

# ── 2. seed 16000 台设备 ─────────────────────────────────
# 默认 jdbc-url=jdbc:mysql://127.0.0.1:3306/es_device，无需显式传
# java -jar "$STRESS_JAR" seed --count 16000 --product snd_ess_pcs \
#      --secret-base sanduo-stress --user root --password "$MYSQL_PASSWORD"

# ── 3. 四档吞吐压测（4万→8万→16万→32万 msg/s，每档 60s）────
# tier=1: throughput --count 2000  --rate 20 --duration 60
# tier=2: throughput --count 4000  --rate 20 --duration 60
# tier=3: throughput --count 8000  --rate 20 --duration 60
# tier=4: throughput --count 16000 --rate 20 --duration 60
# 每档：执行前快照（Broker stats / Kafka LAG / TDengine count(*)）→ 跑 throughput
#       → 执行后快照 → 差异 = 本档端到端处理量 → 写 results/tier-N.*  json
# 判停：某档 avg msg/s 较上档增幅 <15%，或 failures>0.1%×总消息，或 CPU 打满
#       → 该档记为峰值档，停止升档（本机 14 核，预期峰值档出现在 8万~16万 区间）

# ── 4. 演练 05：控制链路 P99 ─────────────────────────────
# java -jar "$STRESS_JAR" control --count 200 --concurrency 50 \
#      --gateway http://127.0.0.1:8000 | tee results/control-p99.txt
# 解析 P99=NNN；≤500ms → PASS

# ── 5. 故障演练 01/02（03/04 报告标注受限）────────────────
# 01 Kafka 重平衡：docker restart ems-kafka → 观测消费组重平衡 + 恢复
# 02 Broker 重启自愈：PowerShell Stop-Process 停 broker → Start-Process 重启
#    → 观测 MQTT 重连 + 消息恢复
# （03 需 docker-redis、04 需管理员停 MySQL → 报告如实标注未执行）

# ── 6. 汇总 ──────────────────────────────────────────────
# 四档数据 + P99 + 故障观测 → results/summary.json
```

### 4.2 新增文件清单

| # | 组件 | 类型 | 职责 |
|---|---|---|---|
| 1 | `test/stress/run-baseline.sh` | NEW 脚本 | P0-5 全流程编排（§4.1），零 Java 改动。 |
| 2 | `test/stress/results/<run-id>/` | NEW 输出目录 | 每档 json + `summary.json` + `control-p99.txt` + 故障观测记录。gitignored 不入库。 |

### 4.3 外部观测采集（每档交叉验证）

| 观测点 | 命令 | 验证什么 |
|---|---|---|
| Broker 接受率 | `curl -s http://127.0.0.1:8082/internal/broker/stats` → `data.messagesIn` 增量 | 设备→Broker 真实入站（对照 stress 的 published 计数，发现客户端侧自欺） |
| Kafka LAG | `docker exec ems-kafka /opt/kafka/bin/kafka-consumer-groups.sh --describe --all-groups` | 消费是否跟得上生产（energy-access-uplink / energy-tsdb-prop / energy-cmd-ack） |
| TDengine 落库 | `SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs` 增量 | 端到端落库率（access→kafka→tsdb→TDengine 全链） |
| P99 | `stress control` 输出 | 控制链路端到端延迟基线 |

## 5. 产出物

1. `test/stress/results/<run-id>/summary.json`：四档吞吐（avg/P50/P95/P99 + 失败率）、峰值档判定、Broker/Kafka/TDengine 三观测点增量。
2. **`docs/design/P0-5-压测基线报告.md`**（NEW）：实测峰值、四档吞吐曲线、P99 基线、瓶颈定位、C-01/C-02/C-03 断言对照、G-1/G-2 差距记录、单节点环境对生产容量的影响说明（REPLICA 1 vs 2、VGROUPS 8 vs 32、单 broker vs 集群）。
3. `docs/design/Phase9-生产化差距分析.md` EDIT：v1.5（+§5.6 P0-5 落地记录）；P0-5 roadmap 行标已落地；C-01/C-02 行附实测值；C-03 标注「演练 01/02/05 已执行，03/04 受限」。

## 6. 错误处理

- 前置检查任一失败（Nacos/Kafka/Broker/TDengine/MySQL 探活）→ 脚本非零退出，打印缺失项，不执行压测。
- TDengine 建库已在「dropping status」时重试（实测 DROP 后需数秒），脚本内置重试循环。
- `stress` 子命令非零退出 → 该档失败，记录并继续下一档（不整体中止），最终报告中如实呈现失败档。
- Kafka 重平衡 / broker 重启演练：先快照 LAG/连接数，恢复后对比，确认自愈；若未自愈 → 报告标注失败。

## 7. 验证（Git Bash，仓库根；`M2=/d/Program Files/maven-repo`）

1. **环境就绪**：11 服务健康（Nacos group ENERGY）；Kafka/TDengine 容器 UP；`curl :8082/internal/broker/stats` 返回 code 0。
2. **脚本 dry 检查**：`bash -n test/stress/run-baseline.sh` 通过。
3. **执行**：`MYSQL_PASSWORD=*** bash test/stress/run-baseline.sh` → 全程日志；`results/summary.json` 含四档 + P99 + 判停档。
4. **交叉验证**：summary 中每档 `Broker.messagesIn 增量 ≈ stress published`；`TDengine count(*) 增量 ≈ 落库数`（允许 tsdb 消费积压）。
5. **报告**：`docs/design/P0-5-压测基线报告.md` 含实测峰值 + P99 + C-01/C-02 对照 + G-1/G-2。
6. **Phase9**：v1.5；§5.6 落地记录存在；C 行状态已更新。

## 8. 明确不做

- **不修瓶颈**：G-1（tsdb 不自动建库）、G-2（DDL 语法/单节点不兼容）以及压测发现的任何瓶颈，只记录进报告，不改代码。
- **不做多节点/集群压测**：本机单节点 Kafka/TDengine，生产容量换算（REPLICA/VGROUPS）仅在报告注明，不实测集群。
- **不引入新压测工具**（JMeter/Gatling 等）：复用现有 `stress` 工具，零依赖。
- **演练 03/04 不执行**（需 docker-redis / 管理员权限停 MySQL），报告标注受限。
