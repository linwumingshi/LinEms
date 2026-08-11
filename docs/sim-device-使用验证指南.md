# sim-device 模拟设备接入与消息验证指南

> 目标：用 `test/sim-device` 交互式单设备模拟器接入 EnergyX 平台，验证两条核心链路：
> **设备上报**（属性/事件/上下线上行）与**平台下行指令**（命令下发 → 设备回 ACK → 指令状态 SUCCESS）。

设计文档见 [`superpowers/specs/2026-08-07-sim-device-design.md`](superpowers/specs/2026-08-07-sim-device-design.md)。

---

## 0. 全链路速览

```
【上报链路】   sim-device --MQTT(1883)--> broker --> Kafka(iot-property) --> energy-tsdb(shadow/TDengine)
【下行链路】   curl(8000) --> gateway --> energy-command --> Kafka(iot-command-down) --> broker --> sim-device
【ACK 链路】   sim-device --up/ack(QoS1)--> broker --> Kafka(iot-command-ack) --> energy-command(状态→SUCCESS)
```

- 设备接入端口：**1883**（明文 MQTT 3.1.1，HMAC 认证）
- 平台 REST 网关：**8000**（`/api/**`，JWT 鉴权，仅 `/api/system/auth` 白名单）
- Broker 运维统计：**8082** `/internal/broker/stats`（无鉴权）
- 主题规范：`{productKey}/{deviceName}/up/{property|event|lifecycle|ack}`、下行 `{pk}/{dn}/down/command`

---

## 1. 前置准备

### 1.1 启动全栈

Docker 基础环境（Nacos/Kafka/Redis/ES/TDengine）+ 本机 MySQL + 11 个后端服务 + 网关：

```bash
# Git Bash，仓库根目录
deploy/scripts/start-stack.sh     # 首次会自动补建缺失 jar；已跑过可加 --skip-build
deploy/scripts/status-stack.sh    # 确认服务/端口/Broker 统计均为 UP
```

> 需要验证的关键端口：`8000`（gateway）、`1883`（broker）、`8082`（broker 统计）、`8114`（command）。
> 前置密钥文件 `deploy/env/local.env`（gitignored）由 `start-stack.sh` 自动加载。

### 1.2 构建 sim-device

```bash
# 1) SDK 先装本地仓库（sim-device 依赖 energyx-device-sdk-1.0.0）
cd sdk/java && mvn install
# 2) 打包 sim-device fat jar
cd test/sim-device && mvn package
# 产物：test/sim-device/target/sim-device.jar
```

### 1.3 注册目标设备（seed 造数）

平台按 `productKey + deviceName` 识别设备、按派生密钥做 HMAC 认证，因此连接前**必须先把设备注册进 es_device 库**（Broker 认证缓存 + MySQL 回源才能放行）。

**默认设备 `sim-dev-000001` 已随 Flyway `V2__seed_device.sql`（energy-device，运行时建表路径）种子初始化**，全新建库即可用、无需额外步骤；设计期 DDL 同步见 `sql/mysql/30_device.sql`。

种子用 `INSERT ... ON DUPLICATE KEY UPDATE` 而非 `INSERT IGNORE`（**自愈设计**）：若设备被逻辑删除（`deleted=1`）后重新 seed，设备行会复活、被吊销凭据（`auth_status=2`）重新激活——避免「删 A 设备再重加 A 连不上」（`iot_device_credential.uk_cred_device` 无 `deleted` 列，普通 API 重加走新雪花 ID 不受影响）。

需要更多/批量设备时，用压测工具 `stress.jar` 造数注册（同款 upsert 幂等自愈）：

```bash
# 需要 MYSQL_PASSWORD 环境变量（密钥外置）。已在 start-stack.sh 中 source 过 local.env 的终端也可直接跑
source deploy/env/local.env

java -jar test/stress/target/stress.jar seed \
  --count 1000 --product snd_ess_pcs --secret-base sanduo-stress
```

**METER 表计设备（需量管理模拟需要）**：energy-product `V2__seed_meter.sql` 只种子了产品与物模型
（productKey=`snd_ess_meter`，唯一属性 `importPower`，单位 kW），**不注册设备行**。需量管理按
`station_id` 找表计，所以 seed 时要带 `--station` 挂到电站、并用 `--start-index` 避开默认设备
`sim-dev-000001`（已被 PCS 占用，`ON DUPLICATE` 不会换产品）：

```bash
java -jar test/stress/target/stress.jar seed \
  --product snd_ess_meter --count 1 --start-index 2 --station <电站ID> --tenant 1
# → 注册 sim-dev-000002（METER @ 该电站），密钥 = deriveSecret("sanduo-stress", 2)
```

> **按电站查询（EMS 需量/收益模拟的硬前置）**：EMS 的需量检测、削峰、收益核算都按
> `es_device.iot_device.station_id` 查设备（不联 `iot_station_device` 关联表）。要让模拟设备被这些
> 功能命中，**seed 必须带 `--station <电站ID>`**，且该电站须已存在（页面创建或 `POST /api/station`）。
> 默认设备 `sim-dev-000001`（station_id 为 NULL）不会被任何电站查询命中，别拿它模拟需量/收益。
> `--start-index N` 从序号 N 造起，多产品/多电站造数时精确错开设备名与号段。

**设备身份派生规则**（`test/stress/.../Secrets.java`，与 sim-device 完全一致）：

| 项 | 值 | 说明 |
|---|---|---|
| deviceName | `sim-dev-%06d`，即 `sim-dev-000001` … | width = max(6, count 位数) |
| deviceSecret | `hex(SHA-256("sanduo-stress:index"))` | index = deviceName 数字后缀（`sim-dev-000001` → 1） |
| deviceId | `8000000000000000000 + index` | seed 专用号段，`sim-dev-000001` → `8000000000000000001` |

> deviceName 禁含 `_` / `&`（Broker 按 clientId 最后一个 `_` 拆分、username 以 `&` 分隔）。

---

## 2. 启动模拟设备并连接

```bash
cd test/sim-device
./sim-device.sh    # 默认：--product snd_ess_pcs --device sim-dev-000001 --secret-base sanduo-stress --broker 127.0.0.1:1883
```

常用参数：

```bash
./sim-device.sh --device sim-dev-000003 --secret-base sanduo-stress   # 换一台已 seed 设备
./sim-device.sh --secret <64位hex>                                    # 显式密钥（与 --secret-base 二选一，优先）
./sim-device.sh --broker 192.168.1.10:1883                            # 非本机 broker
./sim-device.sh --autoack                                             # 启动即自动回 ACK
```

启动即自动连接（`Connector.connect()` 走 SDK HMAC CONNECT + 订阅 `down/command`）。成功输出：

```
EnergyX 平台交互式模拟器
  clientId: snd_ess_pcs_sim-dev-000001
  broker:   127.0.0.1:1883
  autoack:  off
输入 help 查看命令表
已连接 snd_ess_pcs_sim-dev-000001 @ 127.0.0.1:1883
sim-dev>
```

> 连接被拒时按 CONNACK 返回码给中文提示：code 4 = 密码错/设备未 seed；超时/TCP 失败 = broker 未跑或地址错。

---

## 3. 测试设备上报（上行）

在 `sim-dev>` 提示符下执行：

| 命令 | 示例 | 行为 |
|---|---|---|
| `report [k=v ...]` | `report soc=52 voltage=215 current=10` | 发布属性到 `up/property`（QoS0）；无参数 = 按产品随机一组（METER 只报 `importPower`，PCS 报 `{soc,voltage,current,power,temp,runMode}`） |
| `event` | `event overTemp 1 10001 temp=85` | 发布事件到 `up/event`（severity 默认 1） |
| `lifecycle` | `lifecycle online 10.0.0.5` | 发布上下线事件到 `up/lifecycle`（offline 同上） |
| `status` | `status` | 连接态 / clientId / broker / 待处理命令数 / autoack 状态 |
| `help` | `help` | 命令表 |

```text
sim-dev> report soc=52 voltage=215 current=10 power=11.2 temp=28.5
已上报属性: {soc=52, voltage=215, current=10, power=11.2, temp=28.5}
sim-dev> event overTemp 2 10001 temp=85
已上报事件 overTemp severity=2 code=10001 data={temp=85}
sim-dev> lifecycle online 10.0.0.5
已上报上下线 online ip=10.0.0.5
```

### 3.1 验证上报已入库

**a) Broker 统计（确认消息真的进来了）**

```bash
curl -s http://127.0.0.1:8082/internal/broker/stats
# 关注 connections（应 ≥1）、messagesIn（应递增）、authFailures（应为 0）
```

**b) 影子查询（设备最新状态，需 JWT，见 §4.1 登录）**

```bash
# deviceId：seed 设备 = 8000000000000000000 + index，sim-dev-000001 → 8000000000000000001
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/shadow/8000000000000000001
# data.reported 应包含刚上报的 soc/voltage 等
```

**c) TDengine 时序表（属性原始记录）**

```sql
-- 表名 st_prop_{productKey}，子表 dev_{deviceId}
SELECT count(*) FROM st_prop_snd_ess_pcs WHERE tbname='dev_8000000000000000001';
-- 每次 report 后行数递增
```

**d) 服务日志**

```bash
tail -f deploy/logs/energy-tsdb.log    # 属性/时序摄取
tail -f deploy/logs/energy-access.log  # 上行解析
```

### 3.2 模拟 METER 表计上报（需量管理数据源）

需量管理读表计 `importPower`（用电功率 kW）做 15 分钟槽位均值。连接一个 **METER 设备**后上报（无参数 = 按产品随机，只报 `importPower`）：

```bash
# 须用已 seed 的 METER 设备（如 sim-dev-000002，见 §1.3）
cd test/sim-device
./sim-device.sh --product snd_ess_meter --device sim-dev-000002
```

```text
sim-dev> report                    # 随机 importPower（500-3499）
sim-dev> report importPower=2500   # 指定用电功率，造超限尖峰
```

> 上报属性须命中设备所属产品的物模型（METER 仅 `importPower`），否则 access 侧 ModelValidator 拒绝入库。

---

## 4. 平台下发命令到设备（下行）与验证

### 4.1 登录获取 JWT（网关鉴权：除登录/验证码/健康检查/WS 白名单外全强制）

```bash
# admin / admin123（sys_user 初始账号，BCrypt；生产须改密）
curl -s -X POST http://127.0.0.1:8000/api/system/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","tenantId":1}'
# 返回 Result<LoginResponse>：data.token 即 JWT
TOKEN='<上一步 data.token>'
```

### 4.2 创建指令（在线直发）

```bash
curl -s -X POST http://127.0.0.1:8000/api/command \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"productKey":"snd_ess_pcs","deviceName":"sim-dev-000001",
       "command":"setPower","params":{"power":500},
       "commandType":2,"timeoutMs":15000,"maxRetry":3,"createBy":1}'
```

返回（设备在线时为 `SENT`，离线为 `CREATED` 入队）：

```json
{"code":0,"message":"成功","data":{
  "commandId":"1849000000000000001",
  "productKey":"snd_ess_pcs","deviceName":"sim-dev-000001",
  "command":"setPower","params":{"power":500},
  "state":1,"stateName":"SENT",
  "deviceId":8000000000000000001,
  ...}}
```

### 4.3 模拟设备收到指令并回 ACK

sim-device 终端会**立刻中断打印**下行命令：

```
↓ 收到下行命令: CommandMessage{commandId='1849000000000000001', command='setPower', params={power=500}}
sim-dev>
```

然后手动回 ACK（缺省 commandId = 最新一条，缺省 status = SUCCESS）：

```text
sim-dev> ack 1849000000000000001 SUCCESS
已回 ACK 1849000000000000001 → SUCCESS
```

也可以提前开启自动回 ACK，收到即回 SUCCESS：

```text
sim-dev> autoack on          # 或启动参数 --autoack
sim-dev> status              # 确认"自动回 ACK: on"
```

### 4.4 验证指令状态流转（响应验证）

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/command/1849000000000000001
```

关键字段应呈现完整状态机：`SENT` →（收到 ack）→ `SUCCESS`，并带时间戳与结果：

```json
{"code":0,"message":"成功","data":{
  "commandId":"1849000000000000001","state":4,"stateName":"SUCCESS",
  "receivedTime":"2026-08-08T17:00:01","executingTime":"2026-08-08T17:00:01",
  "finishTime":"2026-08-08T17:00:01","result":{"exec":"ok"},
  ...}}
```

**指令状态机**：`0 CREATED` → `1 SENT` → `2 DEVICE_RECEIVED` → `3 EXECUTING` → `4 SUCCESS / 5 FAILED / 6 TIMEOUT`。
sim-device 的 `ack` 仅发 `SUCCESS/FAILED` 终态 ACK（中间态由设备按需发，此处不做）。

### 4.5 完整演练（一次性复制执行）

```text
# 终端 1：模拟设备
cd test/sim-device && ./sim-device.sh

# 终端 2：平台下发 + 验证
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/api/system/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","tenantId":1}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

CID=$(curl -s -X POST http://127.0.0.1:8000/api/command \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"productKey":"snd_ess_pcs","deviceName":"sim-dev-000001","command":"setPower","params":{"power":500},"commandType":2,"timeoutMs":15000,"maxRetry":3,"createBy":1}' \
  | sed -n 's/.*"commandId":"\([^"]*\)".*/\1/p')

# 终端 1 里看到下行命令后回 ACK
#   sim-dev> ack $CID SUCCESS

# 验证终态
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/command/$CID
```

---

## 5. 进阶：离线队列补发

设备离线时创建指令 → 指令保持 `CREATED`，入 Redis 离线队列 `iot:cmd:q:{deviceId}`（`energy-command` 日志 "离线入队"）；设备上线（`lifecycle online`）触发补发，转为 `SENT` 并下发。

```text
# 模拟设备里先断开
sim-dev> disconnect

# 终端 2：创建指令 → 返回 stateName=CREATED

# 模拟设备里重连（触发 lifecycle ONLINE → 补发）
sim-dev> connect          # 或 reconnect
# 应打印下行命令 → ack → 状态 SUCCESS
```

> `energyx.ems.device-name`（Nacos `energy-shared.yaml`，默认 `sim-dev-000001`）是 EMS 策略引擎自动下发的前置目标设备——用默认设备名即可被策略链路命中。

---

## 6. 常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `连接被拒绝: 密码错误或设备未注册`（CONNACK 4） | 设备未 seed / deviceName 与 seed 不一致 / secret 派生错 | 重跑 `stress.jar seed`；核对 `--device` 与 `--secret-base`；或改用 `--secret` 显式密钥 |
| 连接超时 / TCP 失败 | broker 未起 / 地址错 | `deploy/scripts/status-stack.sh` 看 1883；检查 `--broker` |
| `上报时未连接` | 忘了 connect | `sim-dev> connect` |
| API 返回 `40100 未认证或 Token 无效` | 未带或带错 Bearer | 重新登录，`Authorization: Bearer <token>` |
| API 返回 `40101 登录已过期` | JWT 过期（默认 7200s） | 重新登录 |
| `设备不存在: snd_ess_pcs/sim-dev-xxxx` | 该设备未注册或 deviceName 拼错 | 确认 seed 过且名称一致 |
| 创建指令后一直 `CREATED`/`SENT` 不变 | 设备离线（CREATED）/ 在线但没回 ACK（SENT） | 检查 sim-device 是否连接；收到下行后 `ack`；或 `autoack on` 后重试 |
| `TIME OUT` 终态 | 重试耗尽仍未回 ACK | 确认设备在线、broker 到设备链路通；调大 `timeoutMs` |
| TDengine 查不到 `st_prop_snd_ess_pcs` | 子表按 deviceId 建，名字是 `dev_{deviceId}` | `SELECT tbname FROM st_prop_snd_ess_pcs LIMIT 5` 看实际子表名 |

---

## 7. 相关资源

- 交互式模拟器设计：`docs/superpowers/specs/2026-08-07-sim-device-design.md`
- 设备端 SDK：`sdk/java/src/main/java/com/energyx/device/`（MqttDevice / DeviceIdentity / HmacAuth）
- 造数/压测工具：`test/stress/`（`seed` / `connect` / `throughput` / `control` 子命令）
- 需量管理使用手册：`docs/manuals/2026-08-11-ems-demand-management.md`（§7.1 模拟器端到端模拟）
- 收益核算使用手册：`docs/manuals/2026-08-11-ems-revenue-accounting.md`（§7.1 模拟器端到端模拟）
- 指令中心：`backend/energy-command/`（CommandController / CommandService / CommandState）
- 控制链路演练（自动判定 P99）：`test/drill/05-command-p99.sh`
- 设备接入 Broker：`docs/design/Phase4-自研MQTTBroker.md`
- 业务模块（影子/指令/告警）：`docs/design/Phase6-业务模块.md`
