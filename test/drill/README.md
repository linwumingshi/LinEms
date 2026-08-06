# 故障演练手册（Phase 8c）

针对深圳三多能源储能管理平台的关键故障场景，提供**可重复执行**的黑盒演练脚本与判定基准。
每个脚本独立产出 PASS/FAIL 计数；全套脚本覆盖架构承诺的高可用与自愈能力。

## 前置条件（全量演练）

> **推荐先跑** `deploy/scripts/start-stack.sh` 一键拉起全栈（Docker 基础环境 + 11 个后端服务 + 就绪轮询），
> 再用 `deploy/scripts/status-stack.sh` 确认各端口 UP。

| 依赖 | 要求 | 验证命令 |
|---|---|---|
| 平台服务 | 至少 Broker / Redis / MySQL 运行 | `jps -l` 见 `energy-mqtt-broker` |
| 全部服务 | 演练 01/05 需 energy-access、energy-command、energy-gateway | `jps -l` |
| Kafka | 容器 `ems-kafka` 运行 | `docker compose -f deploy/docker/docker-compose.yml ps` |
| 压测工具 | `test/stress/target/stress.jar` 已构建 | `ls test/stress/target/stress.jar` |
| 设备种子 | 产品 `snd_ess_pcs` 已存在（Phase 2/3 seed） | 演练内自动 `stress seed`（幂等） |
| Git Bash | 脚本须在 Git Bash / WSL 运行（依赖 `/dev/tcp`、`jps`） | — |

> MySQL 服务名为 `MySQL`（可用 `MYSQL_SERVICE` 环境变量覆盖）。
> 演练 04 的 `--execute` 模式需要管理员权限的 shell。

## 演练清单

| # | 脚本 | 故障注入 | 核心断言 | 演练时长 |
|---|---|---|---|---|
| 01 | `01-kafka-rebalance.sh` | 重启 energy-access，消费组离组→重平衡 | 分区全部归属；突发后 LAG≤100 归零；重平衡后二次突发仍追平 | ~2min |
| 02 | `02-broker-restart.sh` | kill Broker → 重启 | 端口关闭；SDK 指数退避自动重连；120s 内连接数恢复至 100/100；恢复后可再接入 | ~1.5min |
| 03 | `03-redis-degrade.sh` | `docker stop ems-redis` | 新连接被拒（fail-closed）；既有会话存续；Broker 进程/端口正常；恢复后新连接成功 | ~1min |
| 04 | `04-mysql-failover.sh` | `net stop MySQL`（`--execute`） | 新连接被拒（认证查库失败）；既有会话存续；Broker 存活；恢复后新连接成功 | ~1min |
| 05 | `05-command-p99.sh` | 无（基线压测） | 控制链路 P99 ≤ 500ms；全链路 SUCCESS | ~1min |

## 运行方式

```bash
cd test/drill
./run-all.sh                 # 顺序执行 01→05（04 预演模式）
./run-all.sh --execute       # 04 真实停 MySQL 服务
./run-all.sh 02              # 只跑 02
./01-kafka-rebalance.sh      # 单独执行某个演练
```

所有日志落在 `test/drill/logs/`（如 `02-broker-restart.sh` 的 Broker 进程日志
`logs/energy-mqtt-broker.log`）。

## 判定语义

- 每个演练以 `PASS/FAIL` 汇总；`FAIL` 计数 > 0 时 `run-all.sh` 退出码 1。
- 演练 04 默认**预演模式**：不真正停库，仅校验链路与预案可执行性；
  加 `--execute` 才会真实 `net stop MySQL`，并注册 `trap` 在中断/失败时兜底 `net start`。
- 演练 02 依赖 SDK 自动重连：退避基数 1s、上限 30s，故恢复窗口给足 120s。

## 故障语义与设计依据

1. **认证 fail-closed**：Broker `MqttChannelInboundHandler` 对 `authenticate()` 的任何异常
   （Redis nonce SETNX / MySQL 凭据查询不可用）一律 `AuthResult.deny(3, "认证服务异常")`，
   新连接拒绝；会话簿在进程内存，既有连接不受影响 → 演练 03/04 据此断言。
2. **Broker 会话与下行路由**：会话/订阅存内存 + Redis 共享，Broker 重启后由设备侧自动重连
   重建会话 → 演练 02 依据 SDK 自动重连验证自愈。
3. **消费组重平衡**：`energy-access-uplink`（mqtt.router，4 线程）等组在成员离开/加入时
   由 Kafka 协调器重平衡，单设备消息按 deviceId 分区保证组内保序 → 演练 01 验证
   「重启服务 → 分区重分配 → LAG 归零 → 无丢失」。
4. **控制链路指标**：P99 ≤ 500ms 为架构验收指标（Phase 1 §验收指标），演练 05 固化回归基线。

## 扩展演练（可选）

- **Kafka 单节点故障**：`docker stop ems-kafka`，验证 Producer 缓存/重试与消费端组协调的降级；
  单节点 compose 下该场景偏「全损」演练，建议多 broker 化后再执行。
- **Nacos 不可用**：`docker stop ems-nacos`，验证已注册服务路由继续工作、新服务无法注册。
- **TDengine 不可用**：验证 tsdb 侧写入降级（内存缓冲 → 恢复后回放），当前版本记录为 TODO 候选。
