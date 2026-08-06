# 深圳三多能源储能管理平台 — Phase 7 · 前端开发（Vue3 驾驶舱）

> 版本：v1.0    日期：2026-08-06
> 前置：Phase 1 架构（ADR-001~009）/ Phase 6 业务模块（影子/指令/告警）已完成
> 栈（锁定）：Vue3 / TypeScript / Vite / Pinia / Element Plus / ECharts / Axios

## 1. 设计说明

### 1.1 目标

提供面向运维值班人员的 Web 驾驶舱，直连 Phase 6 三个业务服务的 REST + WebSocket 能力：

| 页面 | 路由 | 数据源 | 核心动作 |
| --- | --- | --- | --- |
| 设备监控（驾驶舱） | `/dashboard` | `GET /api/alarm/records` | 告警统计卡片 + ECharts 趋势/分布 + 最近告警 |
| 影子 | `/shadow` | `GET/PUT /api/shadow/{deviceId}` | 查询 reported/desired 合并视图、下发 desired 并展示 delta |
| 指令中心 | `/command` | `POST/GET /api/command` | 创建指令（在线直发/离线入队）、7 态状态机跟踪 |
| 告警中心 | `/alarm` | `GET /api/alarm/records` + `POST /api/alarm/ack/{id}` + `ws://…/ws/alarm` | 分页检索、人工确认、实时触发/恢复推送 |

### 1.2 架构位置

```
浏览器(5173) ──/api/**──► 网关(8000) ──lb://──► energy-shadow / energy-command / energy-alarm
            └─/ws/alarm─► 网关(8000) ──lb://──► energy-alarm  (/ws/alarm WebSocketHandler)
```

- 前端**不直连**微服务端口，统一走网关（Phase 1 ADR：网关为南向唯一入口，WebSocket 由网关 `WebsocketRoutingFilter` 转发）。
- 开发环境经 Vite dev proxy 把 `/api`、`/ws` 转发到本机网关 8000；生产环境构建产物由 Nginx 同源反代到网关。

### 1.3 数据流与口径

- **实时告警**：`/ws/alarm` 推送 `AlarmMessage`（ACTIVE/RECOVERED），前端只驻留内存（上限 100 条），未读角标自增；
  页面列表权威数据始终来自分页查询（避免与规则引擎状态机耦合，与 Phase 6 `AlarmMessage` 注释一致）。
- **驾驶舱口径**：三个状态卡片（触发中/已恢复/已确认）按 `status=0/1/2` 各查 `page=1&size=1` 取 `total`（精确值）；
  趋势/级别/设备数来自 `page=1&size=500` 样本窗口，图表标题标注"样本"口径。
- **影子 delta**：`PUT desired` 返回 `delta`（desired−reported），前端回显需同步设备差异，随后重拉合并视图展示最新版本。

## 2. 技术决策理由

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | Vue3 `<script setup>` + TypeScript strict | 组合式 API 更贴合「页面数据流 + 组合逻辑」；strict 在编译期拦截空值/类型漂移 |
| D2 | Vite 5（而非 Vue CLI） | 原生 ESM、毫秒级 HMR；`vue-tsc --noEmit` 先做类型门禁再打包 |
| D3 | Pinia（组合式 store）管理告警全局态 | 实时事件流/未读数/连接状态为**跨页面共享**状态（App 挂载即建连，任何页面消费），单一 store 天然去重 |
| D4 | Axios 统一拦截器解包 `Result<T>` | 后端统一响应体 `code=0` 成功；拦截器把 `data` 直接给调用方，`code≠0` 与网络异常统一归一为 `Error`，业务层零样板 |
| D5 | 原生 WebSocket 客户端 + 指数退避 + 心跳 | 网关转发 WS 场景无需第三方库；重连退避（1s×2^n，封顶 8 次）+ 25s 心跳保证长连接韧性（对齐 Broker 心跳设计思路） |
| D6 | ECharts 5 全量引入 + `manualChunks` 拆包 | 驾驶舱图表密集，按 vendor 拆出 `echarts`/`element-plus` 独立 chunk（gzip 后 ~340KB），主入口保持 <50KB |
| D7 | 格式化/聚合函数拆纯函数模块 | 时间展示、级别映射、趋势聚合与 Vue 解耦，直接可单测（无 DOM 依赖） |
| D8 | SocketLike 接口注入 store | 告警 store 依赖接口而非具体类，单测注入替身即可离线验证推送 reducer，无需 mock WebSocket 全局 |
| D9 | Element Plus 全量引入 | 管理端组件覆盖广、图标多，按需导入的按需收益低；全量 + 拆包可接受 |
| D10 | 网关业务路由**不 StripPrefix** | shadow/command/alarm 的 Controller 已映射 `/api/xxx` 前缀（与 system 的 `/tenant` 不同），StripPrefix 会 404；`/ws/**` 直通保证握手路径与后端 Handler 一致 |

## 3. 项目目录结构

```text
frontend/
├── package.json / vite.config.ts / tsconfig.json / vitest.config.ts / index.html
├── src/
│   ├── main.ts                     # 挂载 Pinia/Router/Element Plus(中文)
│   ├── App.vue                     # 根组件：initSocket() 全局建连 /ws/alarm
│   ├── api/
│   │   ├── http.ts                 # Axios 实例 + Result 解包拦截器（纯函数可测）
│   │   ├── shadow.ts / command.ts / alarm.ts   # 网关 REST 封装
│   ├── ws/
│   │   └── alarmSocket.ts          # WebSocket 客户端（重连/心跳/结构校验）+ SocketLike
│   ├── stores/
│   │   └── alarm.ts                # 实时事件流/未读数/连接状态/consume
│   ├── types/models.ts             # 与后端 DTO 一一对应的 TS 类型
│   ├── utils/alarmFormat.ts        # 级别/状态映射、时间格式化、趋势聚合（纯函数）
│   ├── composables/useEChart.ts    # ECharts init/resize/dispose 生命周期封装
│   ├── components/AlarmLevelTag.vue
│   ├── layouts/MainLayout.vue      # 侧边栏 + 告警未读角标 + WS 连接状态灯
│   ├── views/
│   │   ├── Dashboard.vue           # 设备监控驾驶舱
│   │   ├── Shadow.vue              # 影子查询/desired 下发
│   │   ├── Command.vue             # 指令下发 + 状态机跟踪
│   │   └── Alarm.vue               # 告警检索/确认/实时推送
│   └── __tests__（4 个 spec，31 用例）
└── dist/                           # npm run build 产物
```

## 4. 核心代码

### 4.1 网关路由补全（`backend/energy-gateway/src/main/resources/application.yml`）

```yaml
# ---- Phase 6 业务服务（controller 已映射 /api 前缀，故不 StripPrefix）----
- id: energy-shadow
  uri: lb://energy-shadow
  predicates: [ Path=/api/shadow/** ]
- id: energy-command
  uri: lb://energy-command
  predicates: [ Path=/api/command/** ]
- id: energy-alarm
  uri: lb://energy-alarm
  predicates: [ Path=/api/alarm/** ]
# ---- WebSocket 转发：/ws/alarm 实时告警推送到 energy-alarm ----
- id: energy-alarm-ws
  uri: lb://energy-alarm
  predicates: [ Path=/ws/** ]
```

### 4.2 HTTP 解包拦截器（`src/api/http.ts`）

```ts
export function resolveApiBody<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'code' in body) {
    const result = body as ApiResult<unknown>
    if (result.code === SUCCESS_CODE) return result.data as T
    throw new Error(result.message || `业务错误码 ${result.code}`)
  }
  return body as T
}

http.interceptors.response.use(
  (response) => resolveApiBody(response.data),
  (error) => Promise.reject(toFriendlyError(error)),
)
```

### 4.3 告警 store（`src/stores/alarm.ts`，reducer 直测）

```ts
export function handlePush(msg: AlarmPush): void {
  liveEvents.value.unshift(msg)
  if (liveEvents.value.length > LIVE_EVENTS_LIMIT) {
    liveEvents.value = liveEvents.value.slice(0, LIVE_EVENTS_LIMIT)
  }
  if (msg.status === 'ACTIVE') unread.value += 1
}
```

### 4.4 WebSocket 客户端要点（`src/ws/alarmSocket.ts`）

- `connect()` 幂等、`onclose` 后指数退避重连（`min(1000×2^n, 30000)`，上限 8 次）；
- 25s 心跳 `send('ping')`，连接中保活；
- `isAlarmPush` 结构校验（必须 `alarmEventId` 字符串 + `status∈{ACTIVE,RECOVERED}`），非法帧丢弃防脏数据。

### 4.5 驾驶舱聚合（`src/utils/alarmFormat.ts`）

```ts
export function summarizeRecords(records: AlarmRecord[], days = 7): AlarmSummary {
  // 状态计数 / 级别分布 / 去重设备 / 近 7 日按天分桶（buildTrend）
}
```

`buildTrend` 以「今天-days+1 … 今天」建桶，`triggeredTime` 落在桶内则 +1，窗口外丢弃——纯函数，单测覆盖分桶与越界。

## 5. 测试方案

### 5.1 单元测试（Vitest + happy-dom + @vue/test-utils，31 用例全绿）

| 文件 | 覆盖点 | 用例 |
| --- | --- | --- |
| `utils/__tests__/alarmFormat.spec.ts` | 级别/状态/类型映射、时间格式化、`summarizeRecords` 状态+级别+设备去重、`buildTrend` 7 日分桶与窗口外丢弃 | 11 |
| `api/__tests__/http.spec.ts` | `resolveApiBody`：code=0 解包 / code≠0 抛业务异常 / 非 Result 透传；`toFriendlyError`：HTTP 错误取 message→状态码、超时、断网、透传 | 10 |
| `stores/__tests__/alarm.spec.ts` | ACTIVE 前插+未读自增、RECOVERED 不计未读、consume 移除、100 上限截断、clearUnread、SocketLike 注入的推送/连接状态回写、initSocket 幂等与缺省 | 8 |
| `components/__tests__/AlarmLevelTag.spec.ts` | 级别→文案+tag 样式映射、`hideText` 语义（覆盖 Boolean prop 缺省归一为 false 的坑） | 3 |

### 5.2 类型与构建门禁

- `npm run build` = `vue-tsc --noEmit`（strict 全量类型检查）+ `vite build`（产物 + 拆包）。
- 结果：`EXIT=0`，`echarts`/`element-plus` 独立 chunk（gzip ~340KB），主入口 ~16KB。

### 5.3 端到端说明（需后端运行）

```
nacos 8848 → 启动 energy-shadow/command/alarm/gateway → npm run dev → 打开 http://127.0.0.1:5173
```

- Vite 把 `/api`、`/ws` 代理到网关 8000；告警中心顶部状态灯显示 `/ws/alarm` 连接状态；
- 用 MQTT 客户端向 Broker 上报触发属性/事件，观察驾驶舱卡片、告警中心实时时间线与表格刷新。

## 6. 下一阶段任务（Phase 8 · 测试与压力测试）

1. **百万连接压测**：自研 Broker 连接数/吞吐（MQTT 压测脚本 + 资源监控），验证 20 万~50 万 msg/s 上行与分区扩展；
2. **故障演练**：Kafka 消费组 Rebalance、Broker 节点宕机接管、Redis 故障降级、MySQL 主从切换；
3. **控制链路 P99 验证**：指令下发→设备 ACK 全链路压测，核对 ≤500ms 目标；
4. **前端联调回归**：压测数据下的驾驶舱/告警推送稳定性与 ECharts 大数据量渲染。

## 7. 本阶段验收自评

- ✅ 网关补齐 shadow/command/alarm + `/ws` WebSocket 路由（无 StripPrefix，路径一致）
- ✅ 四页全部落地：驾驶舱（精确状态卡 + ECharts）、影子（查询 + desired 下发 + delta）、指令中心（下发 + 7 态跟踪）、告警中心（分页 + 确认 + 实时推送）
- ✅ 全链路走网关，开发代理 / 生产同源双模式
- ✅ 纯函数/接口注入使单测无中间件依赖，31 用例全绿
- ✅ `vue-tsc` strict + `vite build` 通过，产物拆包合理
- ✅ 无伪代码/空方法/TODO；异常统一走 `toFriendlyError` + ElMessage 提示
