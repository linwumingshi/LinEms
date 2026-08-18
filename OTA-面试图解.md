# EnergyX OTA 升级中心 — 架构与流程图解（面试用）

> 核心一句话：Kafka 是控制面消息总线，把「升级中心 OTA」与「设备接入层（Broker + access）」彻底解耦。
> 两套 topic 体系：Kafka topic（服务间）与 MQTT Broker topic（设备侧），由 access 桥接翻译。

---

## 1. 分层拓扑总图

```mermaid
flowchart TB
    subgraph S1[设备侧]
        DEV["储能设备<br/>EMS / PCS / BMS"]
    end

    subgraph S2[Broker · MQTT  ㅤ服务器]
        BR["持有 TCP 长连接<br/>会话 / 订阅"]
    end

    subgraph S3[access · 协议桥接 + 路由中枢]
        ACC1{"上行分流<br/>按 MQTT 主题前缀"}
        ACC2["ota/ 前缀 → 透传<br/>ota.uplink"]
        ACC3["其他 → 物模型标准化<br/>mqtt.uplink"]
        ACC4["下行: 查 Redis 连接注册表<br/>mqtt:conn:deviceKey = nodeId"]
        ACC5["PUBLISH 到该节点<br/>MQTT 主题"]
    end

    subgraph S4[Kafka 消息总线]
        K1["ota.uplink<br/>设备 → OTA 上行"]
        K2["mqtt.down.{nodeId}<br/>OTA → 设备 下行"]
        K3["iot-device-lifecycle<br/>设备上线事件"]
        K4["iot-alarm<br/>升级失败告警"]
    end

    subgraph S5[energy-ota 升级中心 :8118]
        O1["包 / 任务 Controller"]
        O2["任务编排 OtaTaskService"]
        O3["差分 / 验签服务"]
        O4["定时调度器<br/>灰度 / 超时 / 重试"]
    end

    subgraph S6[集成]
        I1["设备中心<br/>Feign 回写版本"]
        I2["告警中心<br/>规则 / 通知"]
    end

    DEV -->|"MQTT publish ota/inform"| BR
    BR -->|"订阅 MQTT"| ACC1
    ACC1 -->|"ota/ 前缀"| ACC2
    ACC2 --> K1
    ACC1 -->|"其他主题"| ACC3
    K1 -->|"唯一消费组"| O2
    O2 -->|"发升级信封"| K2
    K2 -->|"消费"| ACC4
    ACC4 --> ACC5
    ACC5 -->|"PUBLISH {pk}/{dn}/ota/down"| BR
    BR -->|"推送给设备"| DEV

    DEV -.->|"上线 ONLINE"| K3
    K3 -->|"订阅 → 离线补推"| O2
    O2 -->|"失败投告警"| K4
    K4 --> I2
    O2 -->|"Feign 回写"| I1
```

---

## 2. 端到端时序图（在线设备主流程）

```mermaid
sequenceDiagram
    autonumber
    participant Dev as 储能设备
    participant Br as Broker(MQTT)
    participant Acc as access
    participant K as Kafka
    participant O as energy-ota
    participant DC as 设备中心

    Note over O: 管理端创建任务 → 快照设备明细(PENDING)
    O->>K: mqtt.down.{nodeId} (升级通知信封)
    K->>Acc: access 消费
    Acc->>Br: PUBLISH {pk}/{dn}/ota/down
    Br->>Dev: 下发通知(URL/版本/SHA256/签名/分片参数)
    Dev->>Br: HTTPS Range 分片下载(每块 SHA256 校验)
    Dev->>Br: MQTT ota/progress(45, DOWNLOADING)
    Br->>Acc: 订阅到
    Acc->>K: 透传 ota.uplink
    K->>O: 消费 → 状态机推进(下载中)
    Dev->>Dev: 本地升级(AB 分区) + 重启
    Dev->>Br: MQTT ota/inform(version=2.0.0)
    Br->>Acc: 订阅到
    Acc->>K: 透传 ota.uplink
    K->>O: 消费 → 上报版本 == 目标 → 成功(唯一判据)
    O->>DC: Feign 回写 firmware_version
    Note over O: 刷新统计 / 推进灰度 / 任务完成
```

---

## 3. 关键考点速记

- **两套 topic**：Kafka topic（ota.uplink / mqtt.down.{nodeId} / iot-device-lifecycle / iot-alarm）是服务间总线；MQTT topic（ota/inform、{pk}/{dn}/ota/down）是设备侧。access 负责翻译。
- **access 是中间人**：上行按 MQTT 主题前缀分流（ota/ 旁路到 ota.uplink，其余标准化到 mqtt.uplink）；下行按 Redis 连接注册表 mqtt:conn:{deviceKey}=nodeId 找到设备所在 Broker 节点，PUBLISH 到该节点 MQTT 主题。
- **Broker ≠ access**：Broker 是 MQTT 服务器（管连接），access 是桥接/路由应用（管 Kafka↔MQTT 翻译）。
- **成功判据**：设备上报新版本号 == 目标版本（同阿里云），进度 100% 但没上报新版本且超升级超时 → 判失败。
- **离线兜底**：下行查不到 owner → 回落 mqtt.broadcast + 明细保持 PENDING；设备上线触发 iot-device-lifecycle → 补推（双保险）。
- **差分**：DIFF_FIRST 策略按设备当前版本匹配差分包，未命中自动退化全量；双 SHA256 校验（差分包自身 + 合并产物 == 全量包）。
- **灰度**：1%→10%→50%→100%，本批成功率 <95% 且开启自动暂停 → 暂停任务 + 告警。

---

## 4. 场景联动（规则引擎）架构图

```mermaid
flowchart TB
    subgraph SRC[触发源 5 类]
        T1["设备属性上报<br/>iot-thing-property"]
        T2["定时 cron<br/>xxl-job 动态 job"]
        T3["设备上下线<br/>iot-device-lifecycle"]
        T4["告警事件<br/>iot-alarm"]
        T5["手动触发<br/>REST API"]
    end
    subgraph ENG[energy-rule 引擎]
        TM["TriggerMatcher<br/>多触发器 OR"]
        CE["ConditionEvaluator<br/>多条件 AND"]
        DB["防抖 SETNX<br/>rule:debounce"]
        EX["ActionExecutor<br/>独立线程池"]
        RC["恢复状态机<br/>rule:state FIRED/RECOVERED"]
    end
    subgraph ACT[动作出口 4 类]
        A1["设备控制命令<br/>→ command 中心"]
        A2["触发告警<br/>→ alarm 中心"]
        A3["外部通知 webhook"]
        A4["嵌套规则<br/>深度≤5 环检测"]
    end
    subgraph CACHE[热加载]
        C1["进程内缓存 + 维度索引"]
        C2["rule:changed 发布订阅<br/>增量刷新"]
    end

    T1 --> TM
    T2 --> TM
    T3 --> TM
    T4 --> TM
    T5 --> TM
    TM -->|任一命中| CE
    CE -->|满足| DB
    DB -->|首触 SETNX 成功| EX
    CE -->|不满足 边沿| RC
    RC -->|恢复动作| A1
    EX --> A1 & A2 & A3 & A4
    CACHE -.->|规则变更| ENG
```

---

## 5. 设备在线 / 离线状态机（弱网容错）图

### 5.1 在线状态机

```mermaid
stateDiagram-v2
    [*] --> OFFLINE: 初始 / 未连接
    OFFLINE --> ONLINE: CONNECT 建立
    ONLINE --> ONLINE: MQTT 心跳续期(刷 TTL + 连接锁)
    ONLINE --> OFFLINE: 优雅断开 NORMAL
    ONLINE --> OFFLINE: 心跳超时 HEARTBEAT_TIMEOUT
    ONLINE --> OFFLINE: 异地重连 DUPLICATE_CLIENT / KICK
    OFFLINE --> [*]
    note right of ONLINE
        实时权威: Redis iot:online:{deviceId}
        = brokerNode, TTL 30s 心跳续期
        MySQL iot_device.status=3 仅审计视图
    end note
```

### 5.2 弱网 / 离线容错架构

```mermaid
flowchart TB
    DEV["储能设备"]
    subgraph BRK[Broker 节点 / Redis 会话]
        HB["mqtt:node:{nodeId}<br/>节点心跳 TTL 30s"]
        SES["持久会话 mqtt:session/subs/inflight<br/>重连任意节点恢复"]
        OFFQ["mqtt:offline:{deviceKey}<br/>离线消息队列 List"]
        WILL["mqtt:will 遗嘱<br/>宕机不丢 重连补投"]
    end
    subgraph RT[连接路由]
        CONN["mqtt:conn:{deviceKey}<br/>= nodeId 连接锁"]
        ONL["iot:online:{deviceId}<br/>= nodeId TTL 30s 续期"]
    end
    CMDQ["iot:cmd:q:{deviceId}<br/>命令离线队列"]
    LIFE["iot-device-lifecycle<br/>ONLINE / OFFLINE 事件"]
    subgraph CONS[事件消费方]
        AC["access 刷状态 + 录流水"]
        OT["ota 上线补推"]
        CM["command 离线指令补发"]
        RU["rule 上下线触发"]
        SH["shadow 刷在线态"]
    end

    DEV -->|连接 / 心跳| BRK
    BRK -->|上线事件| LIFE
    LIFE --> AC & OT & CM & RU & SH
    DEV -.->|离线期间上行| OFFQ
    DEV -.->|平台下行命令| CMDQ
    CMDQ -->|ONLINE 触发补发| AC
    HB -.->|宕机 30s 缺失| CONN
    CONN -.->|owner 死节点 回落广播| BRK
```

---

## 6. 关键考点速记（场景联动 + 设备状态）

- **场景联动 = TCA 模型**：Triggers[] 是 OR（5 类触发源）、Conditions[] 是 AND、Actions[] 独立执行（4 类动作 + 嵌套规则）。
- **防抖 + 恢复**：SETNX 防高频轰炸；recovery 做边沿触发（满足→不满足时执行恢复动作，不受防抖限制）。
- **热加载不重启**：进程内缓存 + 维度索引，规则变更双写 MySQL + 发 `rule:changed` 发布订阅增量刷新。
- **解耦**：动作只构造请求，设备命令下沉命令中心（幂等/超时/重试/离线队列）、告警下沉告警中心；规则引擎自己只做编排。
- **定时触发用 xxl-job**：动态注册 job（jobKey=rule-{ruleId}），多实例靠 `lock:scheduled:rule-{id}` 分布式锁防重。
- **设备状态双权威**：实时在线态权威在 Redis `iot:online:{deviceId}`（心跳续期 TTL 30s，规则/条件读它）；MySQL `iot_device.status` 仅审计视图。
- **离线/弱网判定的关键**：Broker 连接权威 + MQTT keepalive 超时（HEARTBEAT_TIMEOUT）识别静默断连，不依赖 TCP FIN。
- **弱网不丢的三层**：持久会话（session/subs/inflight 存 Redis，重连任意节点恢复）+ 离线消息队列 `mqtt:offline` + 命令离线队列 `iot:cmd:q`（上线事件触发补发）。
- **死节点规避**：节点心跳 `mqtt:node:{nodeId}` TTL 30s，宕机后 `BrokerNodeResolver` 判死节点 → 下行回落广播/离线队列；优雅停机删心跳 + 释放连接锁让其他节点立即接管。

