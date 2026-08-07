# 交互式单设备模拟器 sim-device 设计

日期：2026-08-07
状态：已批准（方案 A：独立模块 + fat jar + 启动脚本）
关联：`sdk/java`（sanduo-device-sdk-1.0.0）、`test/stress`（构建模式参照）

## 1. 目标

给用户一个**交互式 CLI REPL**，模拟**单台设备**接入三多平台：完成 HMAC 认证接入 Broker（1883）、交互式上报属性/事件、接收平台下发的下行命令并**手动决定是否回 ACK**（可切自动）。用于调试、演示和人工验证设备接入/命令链路。

## 2. 用户场景

1. 用户用 seed 工具注册过设备（或任意已知密钥的设备）
2. 运行 `sim-device`，自动连上 broker
3. 键入 `report soc=52 voltage=215` → 设备向 `up/property` 发布属性
4. 平台下发命令 → REPL 打印 `down/command` 内容 → 用户键入 `ack` → 设备回 `up/ack`
5. `quit` 优雅断开

## 3. 约束

- Java 17，复用 `sanduo-device-sdk-1.0.0`（HMAC 认证、订阅、自动 ACK 全在 SDK）
- 运行期零新增第三方依赖：仅 SDK + jackson + slf4j/logback（与 test/stress 相同）；测试期新增 `junit-jupiter`（test scope）
- 构建方式：maven-shade fat jar + `sim-device.sh` 启动脚本，复刻 `test/stress/pom.xml` 模式
- 不写 MySQL（不需要 seed 能力；设备密钥由 CLI 参数提供或确定性派生）

## 4. 架构与组件

```
sim-device (module: test/sim-device)
├── SimDeviceCli.java      # main：解析 CLI 参数 → 构造 DeviceIdentity → 启动 REPL
├── Repl.java              # 读 stdin 命令循环，分发到 handler，维护 prompt
├── PendingCommands.java   # 下行命令待处理队列（IO 线程入队，REPL 线程 ack/status 取）
└── Connector.java         # 封装 MqttDevice 生命周期：connect/disconnect/reconnect
```

组件边界：
- **Repl**：只负责「读一行命令、解析、调用 handler、打印结果」，不碰 MQTT。
- **Connector**：只负责 MqttDevice 的创建/连接/关闭，暴露 `publishProperty` 等薄封装。
- **PendingCommands**：线程安全队列（`ConcurrentLinkedQueue` + `AtomicBoolean` 待打印标记），IO 线程回调入队并触发打印。

依赖关系：Repl → Connector → MqttDevice(SDK)；Repl → PendingCommands。

## 5. CLI 参数

```
sim-device [--product snd_ess_pcs] [--device sim-dev-000001]
           [--secret-base sanduo-stress | --secret <hex>]
           [--broker 127.0.0.1:1883] [--autoack]
```

- `--product` 默认 `snd_ess_pcs`
- `--device` 默认 `sim-dev-000001`（须与已注册设备一致，deviceName 禁 `_`/`&`）
- `--secret-base` 默认 `sanduo-stress`：确定性派生密钥 `hex(SHA-256(secretBase + ":" + index))`，index 从 `--device` 的数字后缀解析（`sim-dev-000001` → 1）
- `--secret`：显式设备密钥（hex），与 `--secret-base` 二选一
- `--broker` 默认 `127.0.0.1:1883`（明文字段；TLS 不在本期范围）
- `--autoack`：启动即开启自动回 ACK（等价于 REPL 内 `autoack on`）
- 密钥优先级：`--secret` 显式值 > `--secret-base` 派生（默认 sanduo-stress）

## 6. REPL 命令

| 命令 | 语法 | 行为 |
|---|---|---|
| connect | `connect` | （重）连接 broker；失败打印 CONNACK 返回码 |
| disconnect | `disconnect` | 优雅断开（DISCONNECT + 关闭） |
| reconnect | `reconnect` | 断开后重连 |
| report | `report [k=v ...]` | 发布属性；无参数 = 从固定字段集 `{soc,voltage,current,power,temp,runMode}` 随机一组；打印 payload |
| event | `event <name> [severity] [code] [k=v...]` | 发布事件；severity 默认 1 |
| lifecycle | `lifecycle online\|offline [ip]` | 发布上下线事件 |
| status | `status` | 连接态 / clientId / broker / 待处理命令数 |
| ack | `ack [commandId] [SUCCESS\|FAILED]` | 回 ACK；缺省 commandId = 最新一条；缺省 status = SUCCESS |
| autoack | `autoack on\|off` | 切换自动回 ACK（默认 off，若未带 `--autoack`） |
| help | `help` | 打印命令表 |
| quit / exit | `quit` | 断开 + 退出 |

命令解析规则：大小写不敏感；`report`/`event` 的 `k=v` 用空白分隔（值含空格需引号包裹，本期不做引号解析——`k=v` 值取到下一个空白）；未知命令提示 `help`。

## 7. 数据流

**连接**：`Connector.connect()` → `MqttDevice.connect()`（SDK 内部完成 HMAC CONNECT + 等 CONNACK + 订阅 `down/command`）→ `DeviceListener.onConnected`。

**上报**：`report` → `MqttDevice.publishProperty(props)`（QoS0）→ payload `{messageId, dataType:"report", properties:{soc,voltage,current,power,temp,runMode}, ts}` → topic `snd_ess_pcs/{dn}/up/property`。

**事件**：`event` → `publishEvent(name, severity, code, data)` → `{messageId, eventName, severity, code, data, ts}` → `up/event`。

**命令下行**：平台 `POST /api/command` → energy-command → broker → `down/command` → SDK `onCommand` 回调（IO 线程）→ 入 `PendingCommands` + 中断打印（锁保护，避免与 REPL 输入乱行）。

**ACK**：`ack` → `ackCommand(commandId, status, errorCode, result)`（QoS1）→ payload `{commandId, status, errorCode, result, ts}` → `up/ack`。`autoack on` 时 IO 线程收到命令直接回（SDK 已有 `autoAck`，复用 config）。

**断连**：`quit`/`disconnect` → `MqttDevice.close()`（DISCONNECT + 关闭）。

## 8. 错误处理

- **CONNACK 被拒**（`IllegalStateException("连接被拒绝 code=...")`）：打印返回码 + 中文说明（1 协议版本 / 2 clientId 非法 / 4 密码错 / 5 未授权），并提示可能原因（设备未 seed、密钥/deviceName 不符、设备未激活或禁用）。
- **CONNACK 超时 / TCP 失败**：打印连接地址 + 建议检查 broker 是否在跑。
- **上报时未连接**：提示先 `connect`。
- **命令解析失败**：打印该命令用法行（`help` 可看全表）。
- **输入空行 / 未知命令**：静默 / 提示 `help`，不退出。
- **`quit`**：尽力优雅断开；断开失败不阻塞退出。

## 9. 构建与运行

```bash
# 构建（依赖 sdk-1.0.0 已 install 到本地仓库；与 stress 相同，构建顺序 sdk → 本模块）
cd test/sim-device && mvn package

# 运行
./sim-device.sh [选项]            # 包装 java -jar target/sim-device.jar "$@"
# 或
java -jar target/sim-device.jar --product snd_ess_pcs --device sim-dev-000001 --secret-base sanduo-stress
```

`sim-device.sh` 置于 `test/sim-device/`，内容：`exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "$(dirname "$0")/target/sim-device.jar" "$@"`（与 stress 启动方式对齐）。

## 10. 测试

- **单元测试**（`src/test/java`，JUnit 5，随 shade 前 `mvn test` 运行）：
  - `CliArgsTest`：CLI 参数解析（默认值、`--secret-base` 派生、`--secret` 优先级、非法 deviceName 含 `_` 报错）
  - `ReplParseTest`：`report k=v` / `ack [id] [status]` / `autoack on|off` / 未知命令 的解析与错误提示
  - `PendingCommandsTest`：并发入队 + 取最新 + 待处理计数
- **手动冒烟**（不写自动化，依赖真实 broker）：起全栈后跑 `report` 观察 TDengine `st_prop_snd_ess_pcs` 行数增长；平台 `POST /api/command` 后 `ack` 观察命令状态 SUCCESS。

## 11. 明确不做（YAGNI）

- TLS 1883 接入（SDK 支持，本期不做）
- 引号/转义参数解析、多设备并发
- 写库/seed 能力（造数用现有 `stress.jar seed`）
- 自动重连策略、断线重连 UI 提示之外的更多容错
- Windows 原生 `.bat` 启动脚本（Git Bash `sim-device.sh` 已覆盖当前工作流）

## 12. 交付物清单

- `test/sim-device/pom.xml`
- `test/sim-device/src/main/java/com/sanduo/simdevice/{SimDeviceCli,Repl,Connector,PendingCommands}.java`
- `test/sim-device/src/test/java/com/sanduo/simdevice/{CliArgsTest,ReplParseTest,PendingCommandsTest}.java`
- `test/sim-device/sim-device.sh`
