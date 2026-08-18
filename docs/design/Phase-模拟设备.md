# Phase：模拟设备（Mock Device）—— 带前端页面的设备仿真器

## 1. 目标
替代现有"指令中心/控制台"（仅 HTTP 表单、看不到设备实时态与原始报文）的体验短板，提供一个**带前端页面的模拟设备**项目：
- 在 UI 上一键"新建/启动/停止"若干台仿真设备，每台都作为**真实设备**接入平台（连 MQTT broker、过 HMAC 鉴权、出现在设备列表/影子/指令中心）。
- 支持属性上报、事件上报、上下线、接收并应答平台命令、**查看影子**，以及 OTA 全流程仿真。
- **扩展性约定**：今后平台每新增一个"跟设备打交道"的功能，本模拟器同步补上对应的仿真能力（清晰的扩展点），用于闭环验证平台功能是否完善。

## 2. 架构（推荐：独立后端模块 + 前端页）
```
浏览器 (Simulator.vue)
   │  REST /api/mock/**  (经网关 8000) + WS /ws/mock (经 vite 代理 → 8119)
   ▼
energy-mock-device (新模块, 端口 8119)
   │  Paho MQTT 客户端 (原生 TCP → broker:18831, HMAC-SHA256 鉴权)
   ▼
energy-mqtt-broker:18831  ──上行──▶  energy-access ──▶ shadow/command/ota
                          ◀─下行──  (down/command, ota/down)
```
**为什么是后端模块而非纯前端 mqtt.js**：探查确认 broker **只暴露原生 MQTT TCP(18831)，没有 MQTT-over-WebSocket 监听器**（NettyServerConfig.java:144-161；全仓唯一 WS 是 alarm 的 `/ws/alarm`）。浏览器 mqtt.js 只能走 WS，所以纯前端直连不可行。后端模块用 Paho(本地仓库已有 `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`) 走原生 TCP，再自有 WS 给前端。

## 3. Broker 对接细节（来自探查实证）
- **地址/端口**：`tcp://<broker-host>:18831`（BROKER_MQTT_PORT，1883 被 Hyper-V 保留段覆盖，已改 18831）。
- **鉴权契约**（DeviceAuthService.java:30-35 / HmacSigner.java:28-30）：
  - `clientId = {productKey}_{deviceName}`（deviceName 禁含 `_`，按最后一个 `_` 拆分）
  - `username = {clientId}&{timestamp}&{nonce}`
  - `password = hex(HMAC-SHA256(deviceSecret, username))`，时间窗 ±2min、nonce 一次性
- **上行 topic**：`{productKey}/{deviceName}/up/{property|event|lifecycle|ack}`
  - 属性 payload：`{"dataType":"report","ts":<ms>,"properties":{"soc":88.5,"power":5000}}`（access `UplinkProcessor.processProperty` 解析）
  - 事件 payload：`{"eventName":"...","severity":...,"data":{...}}`
  - 应答 payload：`{"commandId":"...","status":"success","result":{...}}`（→ up/ack）
- **下行 topic**：`{productKey}/{deviceName}/down/command`（属性设置经影子 delta → 命令桥接也走此 topic；无独立 down/property）。设备需订阅 `{pk}/{dn}/down/#`。
- **OTA topic**：下行 `{pk}/{dn}/ota/down`；上行 `{pk}/{dn}/ota/{inform|progress|result|pull}`。
- **设备必须存在于** `iot_device` + `iot_device_credential(auth_status=1)`，且主状态 ∈ {2 离线,3 在线,5 禁用} 才能通过 broker 鉴权（DeviceAuthService.loadCredential）。

## 4. ⚠️ 必要前置修复：TopicAcl 放行 OTA 的 ota/*
探查确认（TopicAcl.java:18-49）broker ACL **白名单制**：
- publish 仅放行 `up/{property|event|lifecycle|ack}`；
- subscribe 仅放行 `{pk}/{dn}/down/*`。
- → `ota/inform|progress|result|pull`(publish) 与 `ota/down`(subscribe) **全部被拒**（连接会被关/订阅 SUBACK 失败）。

**这不仅阻断模拟器，真实设备的 OTA 设备侧链路现在也是断的**（OtaDownPublisher 发 `ota/down`、设备回 `ota/*` 均被 ACL 拒绝）。本任务一并修复：
- `TopicAcl.UP_TYPES` 增加 `ota` 分支（按 `{pk}/{dn}/ota/{inform|progress|result|pull}` 校验）；
- `canSubscribe` 增加 `{pk}/{dn}/ota/down` 放行。
- 修复后不影响既有 `up/*`/`down/*` 语义，仅补足 OTA 命名空间（符合平台 OTA 协议约定）。

## 5. 设备密钥获取策略（需用户拍板，见末尾问题）
模拟器必须持有设备 `deviceSecret` 才能过 broker 鉴权。现有 energy-device 仅在 `regenerateSecret` 返回明文，无"取现有密钥"接口。两条路线：
- **A（推荐，自动建档）**：模拟器在 energy-device 侧自动创建设备并取回密钥——UI 一键"新建模拟设备"即选产品+起名，后端 Feign 建设备→`regenerateSecret` 拿明文 secret→存本地运行时注册表→连 broker 上线。需给 energy-device 加**内部 Feign 接口**（不挂网关）返回密钥。
- **B（接管已有设备）**：模拟器连接你已在平台建好的设备，密钥在 UI 粘贴（从设备页"重生成密钥"复制）。零后端改动、零密钥暴露面，但每加一台需粘一次。

## 6. 模块结构（energy-mock-device, 端口 8119）
```
energy-mock-device/
  config/      MockDeviceProperties (broker 地址/端口, 连接并发上限)
  mqtt/        MqttAuth (HMAC 签名) + SimulatedDevice (每台设备一个 Paho 连接 + 订阅/发布)
  service/     SimulatorService (注册表: deviceId↔secret↔连接态↔最近收发报文)
  ws/          MockWebSocketHandler (/ws/mock 推送: 连接状态/收到命令/OTA 下发/生命周期)
  web/         MockDeviceController (REST 控制面)
```
- **REST**（`/api/mock`）：
  - `GET  /devices` 列表（含在线态、最近上报、待处理命令数）
  - `POST /devices` 新建（A:自动建档 / B:填 productKey+deviceName+secret）
  - `POST /devices/{id}/start` `POST /devices/{id}/stop` `DELETE /devices/{id}`
  - `POST /devices/{id}/report` 发属性/事件（body: type, properties/eventName/data）
  - `POST /devices/{id}/lifecycle` 上线/下线
- **WS**（`/ws/mock`，vite 代理 `ws://localhost:8119`）：服务端→前端实时推送 `connection`/`command`/`ota-down`/`log` 事件，前端展示原始报文与"命令到达即自动/手动应答"。
- **扩展点**：`SimulatedDevice` 统一封装"收到下行→回调"。新增平台功能时只需在 `SimulatedDevice` 加一类下行处理（如新增一种命令类型、新的 ota 阶段），UI 加对应面板即可，符合"跟设备打交道的功能同步补仿真"。

## 7. 前端页面（Simulator.vue）
- 左侧：模拟设备列表（在线/离线徽标、一键新建/启动/停止/删除）。
- 右侧：选中设备详情 —— 属性上报表单（键值对，发送）、事件上报、上下线按钮、最近上行记录；**实时下行面板**（命令到达高亮、原始 JSON、一键"成功/失败"应答）；**OTA 面板**（收到 ota/down 展示固件 URL/版本，模拟进度 0→100%、成功/失败回传）。
- 复用现有 `http.ts`（自动带 token + x-tenant-id）、`MainLayout.vue` 菜单常量、`router/index.ts` 路由（两处同步，照 `Command.vue` 结构）。

## 8. 网关 / 菜单注册
- 网关 `application.yml`(gateway) 增加 `energy-mock-device` 路由 `/api/mock/**` → 8119（与 ota 同款）。
- `MainLayout.vue` 菜单（设备运维 group）加「模拟设备」，路由 `Simulator.vue`。
- vite.config：增加 `/api/mock` 与 `/ws/mock` 代理（参照 `/api/ota` 做法）。

## 9. v1 功能范围（默认，可勾选增减）
- ✅ 属性上报、事件上报、上下线生命周期
- ✅ 命令接收 + 自动/手动应答（验证"指令中心"闭环）
- ✅ 影子查看（读 reported/desired，验证 set 下发是否到达）
- ✅ OTA 全流程仿真（依赖第 4 节 ACL 修复）
- 后续：子集灰度仿真（对接 Phase-OTA-子集灰度）、批量压测模式

## 10. 验证
- 新建 1 台模拟设备 → 平台"设备管理"出现在线设备、"影子"有 reported。
- 指令中心下发命令 → 模拟器实时收到、应答 → 指令中心状态走到"成功"。
- 影子 desired 设置 → 模拟器收到 down/command(set) → 回 ack。
- OTA 建任务(指定设备) → 模拟器收到 ota/down → 进度回传 → 平台标记成功、firmware_version 回写。
