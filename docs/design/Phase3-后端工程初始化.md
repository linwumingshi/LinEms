# Phase 3 · 后端工程初始化 — 设计说明

> 版本：v1.0 ｜ 日期：2026-08-06 ｜ 阶段：后端工程初始化
> 上游依赖：Phase 1 架构（七层 + 自研 Broker）、Phase 2 数据库设计（8 个 es_* 逻辑库 + DDL）
> 验收对照：Phase 1 §13（工程结构 / 统一返回 / 异常体系 / 链路追踪 / 依赖环境可运行）

---

## 1. 设计说明

本阶段把 Phase 1/2 的蓝图落成可编译、可运行、可扩展的 Maven 多模块工程骨架，并打通「网关 → 微服务 → MySQL/Flyway」的最小闭环，为 Phase 4~6 的接入与业务开发提供地基。

### 1.1 交付范围

| 交付物 | 说明 |
| --- | --- |
| Maven 父工程 | Java 17 / Boot 3.2.5 / Spring Cloud 2023.0.1 / SCA 2023.0.1.0 / MyBatis-Plus 3.5.7 版本矩阵统一管理 |
| energy-common | 基础库：统一响应 / 异常体系 / 审计实体 / 链路追踪 / 雪花 ID / Redis / 幂等 / 分布式锁 / MQTT Topic 规范 / 15 个 Kafka topic 常量 |
| energy-gateway | 响应式网关：路由 / 鉴权门卫 / traceId 注入 / CORS |
| energy-system | 系统域：完整 SysTenant 垂直切片（Controller→Service→Mapper→Entity）+ Flyway V1 |
| energy-product / device / station | 三个域服务骨架：Application + 配置 + Flyway V1 + 通用 /ping 探针 |
| 测试 | energy-common 纯单元测试（雪花 ID 单调/唯一、MQTT Topic 规范） |

### 1.2 为什么先搭骨架而不是先写业务

- **验证技术栈能跑**：Boot 3.2 的 WebFlux 网关与 Servlet 服务共存、Nacos 服务发现、Flyway 幂等迁移、MyBatis-Plus 3.5.7 与 Boot 3.2 兼容性，都是 Phase 4 接入模块的前置条件，越早发现成本越低。
- **锁定横向模式**：统一响应/异常/审计字段/分页这些横切面一旦铺开，后期 16 个服务只增业务不增样板。
- **给面试一个"可运行"的起点**：`mvn compile` 通过 + 四服务可注册 Nacos + 网关可路由，本身就是可展示的工程化成果。

---

## 2. 技术决策（含理由）

### D1. 模块按「域」拆分，而非按「接入/业务」拆分
理由：与 Phase 2 的 es_* 逻辑库一一对应（system→es_system …），Flyway 迁移天然归属单库，职责清晰；接入类服务（mqtt-broker/access/message/tsdb）在 Phase 4/5 按父 POM 预留的增量机制加入，不影响已建模块。

### D2. 网关独立于 energy-common，不共享基础库
理由：energy-common 引入 `spring-boot-starter-web`（Servlet/Tomcat），而 Spring Cloud Gateway 是 **WebFlux（响应式）**，两者共存会触发 `spring-webmvc` 与 `spring-webflux` 冲突，导致网关无法启动。故网关自包含：traceId 注入、鉴权门卫、CORS 均为 WebFlux 实现。

### D3. 统一返回 + 业务异常 + 全局兜底（横切面下沉 common）
- `Result<T>`：code/message/data/traceId/timestamp，构造时自动取 `TraceContext`。
- `ErrorCode`：分段错误码（0 成功 / 4xxxx 客户端 / 1xxxx 设备域 / 2xxxx 策略告警 / 5xxxx 系统）。
- `GlobalExceptionHandler`：`@RestControllerAdvice` 兜底参数校验、请求不可读、方法不支持、404、兜底 Throwable，保证任何异常都返回结构一致的 JSON。
理由：16 个服务共享同一错误契约，前端与设备 SDK 只需认一套 code 体系。

### D4. traceId 全链路贯通（网关 → 服务 → Redis/Kafka 日志）
- 网关 `TraceIdFilter`（Ordered.HIGHEST_PRECEDENCE）：请求无 `x-trace-id` 则生成，注入请求头 + 回写响应头。
- 服务侧 `TraceFilter`（energy-common）：读取 `x-trace-id` 写入 **MDC**，日志自动携带；响应头回传。
- 下游 DB 调用、Redis、Kafka 生产者均可通过 MDC 拿到 traceId。
理由：百万级设备联调、告警回溯时，「一次上报=一条 trace」是排障的刚性需求。

### D5. 审计字段 + 逻辑删除自动填充
`BaseEntity`（create_time/update_time/deleted）+ `AuditMetaObjectHandler`（MetaObjectHandler）自动填充，实体只需继承；`@TableLogic` 逻辑删除全局生效（yml `logic-delete-field: deleted`）。理由：储能平台设备/指令/告警数据必须可追溯、可审计，不允许物理删除。

### D6. Flyway 幂等迁移策略（baseline-version=0 + IF NOT EXISTS / INSERT IGNORE）
本地库已由 `sql/mysql/*.sql` 手工初始化，Flyway 需要：
- 种子库（已有表）：`baseline-on-migrate=true` + `baseline-version=0` → 生成历史表，V1 全量语句幂等跳过，**不报错**；
- 全新库：V1 直接建表 + 灌种子，一次到位。
理由：同一份 V1 同时服务"已手工建库的本地环境"与"CI/新环境的干净库"，避免两套脚本漂移。

### D7. 数据源连接本地 MySQL 8.4.6，其余依赖走 docker-compose
MySQL 8 已在本机以服务运行（root/${MYSQL_PASSWORD}，127.0.0.1:3306）；Redis/Kafka/Nacos/ES/TDengine 由 `deploy/docker/docker-compose.yml` 一键拉起。理由：开发期资源可控，接入类服务（Phase 4）再引入更多基础设施。

### D8. SysTenant 作为垂直切片样板
system 服务提供完整 CRUD（分页 / 详情 / 新增 / 修改 / 逻辑删除 / 启停），其余服务复制该模式即可。理由：把"一个业务域的完整最佳实践"先打样，后续 15 个服务照此展开，风格统一。

### D9. 【环境适配】Phase 3 暂缓引入 spring-boot-starter-kafka
**现象**：本机 Maven 实际本地仓库为 `D:\Program Files\maven-repo`（安装级 settings 重定向），远程为内网 Nexus/阿里云镜像（`mirrorOf=central`）。经 `dependency:get` + `-U` + 直连 Central（被网络策略拦截）多路验证：**该镜像完全剔除 `spring-boot-starter-kafka` 构件（3.2.5/3.3.2 均 404，BOM 亦不含该条目）**，属环境硬约束。
**决策**：Phase 3 移除 kafka starter 依赖。理由：
- Phase 3 无任何 Kafka 运行时使用（`KafkaTopicConstant` 为纯常量，`spring.kafka.*` 配置项为前瞻占位）；
- 保留一个不可解析的依赖会阻断整个工程编译，代价高于收益；
- 架构与技术栈承诺不变，Phase 5 消息处理模块引入时，需先解决镜像问题（换 `maven.aliyun.com/repository/public`、配置直连 Central、或手工导入 `spring-boot-starter-kafka`），并在 energy-common 恢复依赖（pom 内已留注释）。

---

## 3. 项目目录结构

```text
backend/
├── pom.xml                        # 父 POM：统一版本矩阵（Spring Cloud / SCA / MP / Redisson）
├── energy-common/                 # 基础库（所有 Servlet 服务共享）
│   └── src/main/java/com/energyx/common/
│       ├── model/                 # Result<T> / PageResult<T>（统一返回 + 分页）
│       ├── exception/             # ErrorCode 枚举 / BusinessException / GlobalExceptionHandler
│       ├── entity/                # BaseEntity（审计字段 + 逻辑删除）
│       ├── web/                   # TraceContext / TraceFilter / PingController
│       ├── config/                # JacksonConfig / RedisConfig / MybatisPlusConfig / AuditMetaObjectHandler
│       ├── redis/                 # RedisUtils / IdempotencyUtils / RedisLockUtil
│       ├── util/                  # SnowflakeIdGenerator
│       ├── constant/              # Constants（头/状态常量）/ KafkaTopicConstant（15 topic）
│       └── mqtt/                  # MqttTopicUtil（Phase 4 Topic 规范先行锁定）
├── energy-gateway/                # WebFlux 网关（8000）
│   ├── EnergyGatewayApplication.java
│   ├── config/CorsConfig.java
│   ├── filter/TraceIdFilter.java / GlobalAuthFilter.java
│   └── resources/application.yml  # lb:// 路由 4 服务 + Nacos discovery + 鉴权白名单
├── energy-system/                 # 系统域（8101）：SysTenant CRUD 垂直切片 + V1__init_system.sql
├── energy-product/                # 产品域（8102）：骨架 + V1__init_product.sql（含 PCS 物模型种子）
├── energy-device/                 # 设备域（8103）：骨架 + V1__init_device.sql
└── energy-station/                # 电站域（8104）：骨架 + V1__init_station.sql
```

### 端口与路由

| 服务 | 端口 | 网关前缀 | Nacos 服务名 |
| --- | --- | --- | --- |
| energy-gateway | 8000 | — | energy-gateway |
| energy-system | 8101 | /api/system/** | energy-system |
| energy-product | 8102 | /api/product/** | energy-product |
| energy-device | 8103 | /api/device/** | energy-device |
| energy-station | 8104 | /api/station/** | energy-station |

---

## 4. 核心代码要点

### 4.1 统一响应（energy-common）
```java
public static <T> Result<T> ok(T data)          // 0/成功/data
public static <T> Result<T> fail(ErrorCode ec)  // 业务码/消息
```
所有 REST 接口返回 `Result`，业务异常 `throw new BusinessException(ErrorCode.CONFLICT, "租户编码已存在")`，由全局兜底转 JSON，无需每接口 try-catch。

### 4.2 鉴权门卫（网关，Phase 3 骨架版）
- 白名单：`/api/system/auth/**`、`/api/system/captcha`、`/actuator/**` 直接放行；
- 其余请求必须携带 `Authorization: Bearer <token>`，提取后以 `x-user-token` 头透传下游；
- 缺失/非法返回 401 JSON。
- **Phase 6 升级**：JWT 解析 + 权限点校验 + 刷新令牌，本类职责边界保持清晰。

### 4.3 幂等与分布式锁（为 Phase 4/5 预热）
```java
idempotencyUtils.tryAcquire(commandId, 3600)   // SETNX 指令去重（ADR-009）
redisLockUtil.tryLock(key, ttlSeconds)         // 简易分布式锁，Phase 6 可换 Redisson
```

### 4.4 网关 → 服务最小闭环验证
```text
curl localhost:8000/api/system/ping            # 网关 → Nacos → energy-system → Result JSON
curl localhost:8000/api/system/tenant/page     # 需要 Bearer token（骨架门卫生效）
```
`/ping` 由 common 的 `PingController` 注入所有服务，验证路由与服务发现。

---

## 5. 测试方案

| 类型 | 用例 | 工具 |
| --- | --- | --- |
| 单元测试 | 雪花 ID：正数 / 1M 唯一 / 严格递增 / 字符串一致性 | JUnit 5 |
| 单元测试 | MQTT Topic：上下行规范 + clientId 拼接 | JUnit 5 |
| 集成（Phase 6 补） | SysTenant CRUD 全链路 MockMvc + H2/本地 MySQL | MockMvc |
| 连通性冒烟 | 4 服务启动 + Nacos 注册 + 网关路由 /ping | curl + 日志 |
| Flyway 迁移 | 种子库重复执行 V1 不报错；全新库可建表 | flyway `validate`/启动日志 |

> 说明：Spring 上下文类测试（@SpringBootTest）依赖 Nacos/MySQL/Redis 在线，故 Phase 3 仅保留纯单元测试；连通性冒烟在本机环境验证（见 §7）。

---

## 6. 下一阶段任务（Phase 4 · 设备接入模块）

1. `energy-mqtt-broker`：Netty 服务端（MQTT 3.1.1/5.0 codec）、连接/消息处理器、编解码器；
2. 设备认证钩子：HMAC-SHA256 签名 + nonce 防重放 + ACL（对接 iot_device_credential）；
3. 会话管理：Redis session/sub/inflight/offline key（规范见 `docs/design/Redis-key规范.md`）；
4. keepalive 1.5× 心跳 + 优雅停机（MQTT5 DISCONNECT 0x8B）+ 跨节点路由 topic `mqtt.router`；
5. Broker 压力测试脚手架（100 万连接仿真方案先行）。

---

## 7. 验证记录（本阶段）

- [x] `mvn -DskipTests compile` **六模块 BUILD SUCCESS**（energy-common/gateway/system/product/device/station）
- [x] 单元测试 **7 通过 / 0 失败**：SnowflakeIdGeneratorTest（正数/1M 唯一/严格递增/十进制字符串）、MqttTopicUtilTest（上下行 topic + clientId）
- [ ] 四服务启动 + Nacos 注册 + 网关 /ping 连通性冒烟（依赖本机 Nacos/MySQL/Redis 容器就绪，Phase 4 起随接入链路一并验证）
- [x] 环境适配：kafka starter 镜像缺失 → 决策 D9（暂缓引入 + Phase 5 前置解决）
