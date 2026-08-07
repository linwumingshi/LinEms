# Phase 9 · 生产化差距分析（Production Readiness Gap Analysis）

> 版本：v1.4（+§5.5 P0-4 密钥外置与 Nacos 客户端凭据）｜ 日期：2026-08-06 ｜ 类型：差距评估 / 缺陷追踪
> 范围：排除中间件集群化（Nacos/Kafka/Redis/MySQL/ES/TDengine 集群）——集群化属基础设施决策，另立专项。
> 评估方式：**基于实际代码核对**（grep + 源码阅读），非文档自评。所有结论均标注证据位置。

---

## 1. 结论

**当前项目不满足生产要求。** 作为面试/原型项目，工程质量高：八阶段全交付、代码可运行、SDK 测试 25/25 通过、11 个服务产物齐全、故障演练脚本成套、幂等与降级设计具备生产思路。但对照生产指标（百万设备 / 20~50 万 msg/s / 500 万 points/s / 控制 P99≤500ms / 99.95% 可用性），**没有一项被实测验证**，且存在安全、数据生命周期、可观测性、运维四类硬缺口，其中鉴权为"空壳实现"（表面有、实际不生效）。

一句话定性（面试口径）：

> 这是一个工程结构完整、可运行的架构原型，核心链路与幂等/降级设计具备生产思路；但当前是单机验证、鉴权未落地、容量指标未实测。按生产标准需补齐安全、观测、数据生命周期、高可用运维四块，才能承载声明中的规模。

---

## 2. 评估方法与证据基线

本次评估的所有结论来自以下实测（非凭印象）：

| # | 核对项 | 方法 | 结果 |
|---|---|---|---|
| E1 | 网关鉴权是否真实验签 | 读 `energy-gateway/.../filter/GlobalAuthFilter.java` + grep `JwtUtil\|Jwts.\|parseToken\|validateToken` | 仅校验 `Authorization: Bearer <非空>` 存在，**无解析/验签/过期检查**；JWT 库全后端零命中 |
| E2 | 前端是否有登录/鉴权 | 遍历 `frontend/src/` 文件 + grep `login\|token` | **无登录页、无 token 存储、无路由守卫** |
| E3 | MQTT 是否支持 TLS | grep broker 模块 `ssl\|8883\|tls` | 已落地（P0-3）：8883 Netty SslContext 双监听，见 §5.4 |
| E4 | 日志是否进 ES | grep `logstash\|filebeat\|logback 投递` | **零命中**；ES 仅有告警记录写入（`AlarmEsWriter`） |
| E5 | 是否有数据归档/保留策略 | grep `retention\|归档\|保留策略\|archive` | 命中的全是 MQTT 保留消息 / Redis session TTL，**无业务表归档任务** |
| E6 | ShardingSphere 是否接入 | grep `sharding\|ShardingSphere` | **零命中**，`sql/mysql/sharding/` 仅为模板表 |
| E7 | 是否有监控指标/链路追踪 | grep `micrometer\|prometheus\|zipkin\|sleuth` | **零命中**（1 处偶然匹配为注释） |
| E8 | 定时任务是否分布锁保护 | grep `@Scheduled` | 4 处：CommandTimeoutScanner / TsdbFlushScheduler / AlarmService / CommandService，**均无分布式锁** |
| E9 | 压测/演练是否真实执行 | 检查 `test/drill/logs/` | **目录为空**，演练 01~05 均未跑通（Docker 不可用） |
| E10 | SDK 上行 QoS | 读 `sdk/java/.../MqttDevice.java` | 属性/事件/生命周期 **QoS0**，仅指令 ACK QoS1 |
| E11 | 是否有 CI/CD / 产物 | 查 `.github`/Jenkinsfile/gitlab-ci/Dockerfile | **均不存在** |
| E12 | 凭据管理 | 读各模块 `application.yml` + `docker-compose.yml` | 已落地（P0-4）：密钥迁 Nacos `energy-shared.yaml`（group ENERGY），仓库零明文（grep 通过），客户端凭据注入，见 §5.5 |

---

## 3. 差距分析矩阵

按域分组，每项含严重度（🔴致命 / 🟠高 / 🟡中）与证据编号（对应 §2）。

### 3.1 安全域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| S-01 | **网关鉴权为空壳**：`GlobalAuthFilter` 仅做 Bearer token 存在性检查即放行，不验签、不查过期、不解析；注释自述"Phase 6 升级 JWT+RBAC"但从未落地 | E1 | 🔴 |
| S-02 | **前端无登录/RBAC**：所有页面直连 API，无登录态、无权限点 | E2 | 🔴 |
| S-03 | **MQTT 无 TLS**：设备接入明文 1883，设备密钥与控制指令可被窃听/篡改 | E3 | 🔴 |
| S-04 | **凭据明文入库**：`root/${MYSQL_PASSWORD}` 写死各 `application.yml`；Nacos 无认证 | E12 | 🟠 |
| S-05 | **无 IP 维度限流**：认证失败熔断按设备维度（10 次/300s），单 IP 可打爆认证/网关 | E1+代码 | 🟠 |
| S-06 | 网关 `x-user-token` 透传无签名：下游服务默认信任该头，若任何内网服务可被直连，则鉴权可被旁路 | E1 | 🟠 |

### 3.2 容量与性能验证域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| C-01 | **容量声明未实测**：1M 设备 / 20~50 万 msg/s / 500 万 points/s 无任何压测数据支撑 | E9 | 🔴 |
| C-02 | **控制 P99≤500ms 无基线**：演练 05 是唯一覆盖脚本，从未执行 | E9 | 🔴 |
| C-03 | **故障演练未端到端执行**：`test/drill/logs/` 为空，自愈能力（重连/重平衡/降级）停留在"脚本可运行"层面 | E9 | 🟠 |
| C-04 | 99.95% 可用性无 MTTR 数据、无恢复时间测量 | — | 🟠 |

### 3.3 存储与数据生命周期域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| D-01 | **分库分表未接入**：全链路单 MySQL，`sql/mysql/sharding/` 仅模板表；声明容量下单表必爆 | E6 | 🔴 |
| D-02 | **无业务数据归档/保留**：影子历史、指令历史、告警、`iot-raw` 原始留痕、上下线记录只增不删 | E5 | 🟠 |
| D-03 | **无备份/恢复预案**：生产库无备份与演练，故障只能靠 Kafka 重放补救 | E11 | 🟠 |
| D-04 | `iot-raw` 留痕无滚动清理：长期运行磁盘必然耗尽 | E5 | 🟡 |

### 3.4 可观测性域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| O-01 | **无指标管线**：无 Micrometer/Prometheus，业务/资源指标均不可采集 | E7 | 🟠 |
| O-02 | **无链路追踪**：无 Zipkin/Sleuth，跨服务调用无法定位瓶颈 | E7 | 🟠 |
| O-03 | **日志未进 ES**：只有告警记录入 ES，设备日志/操作日志管道不存在（有 mapping 无投递方） | E4 | 🟠 |
| O-04 | 无告警规则与大盘：Broker `8082/internal/stats` 为调试端点，非监控管线 | — | 🟡 |

### 3.5 可靠性细节域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| R-01 | **@Scheduled 无分布式锁**：4 处定时任务多实例部署会重复扫描 → 指令重复超时重发、重复告警 | E8 | 🟠 |
| R-02 | **上行 QoS0 丢失窗口**：属性/事件/生命周期 at-most-once，设备重连间隙数据不重发；at-least-once 从 Kafka 之后才开始 | E10 | 🟠 |
| R-03 | **去重依赖 messageId 且缺省退化**：设备未带 messageId 回退雪花 ID，Kafka 重放同报文会被当两条处理 | E10+Phase5§5.2 | 🟠 |
| R-04 | **Redis 挂时去重"记日志放行"**：靠 TDengine 覆盖兜底，语义上存在短暂重复写入 | Phase5§5.1 | 🟡 |
| R-05 | **认证路径单 Redis 依赖**：nonce SETNX / 会话共享在 Redis，Redis 挂 → 新连接全拒（fail-closed 正确，但可用性上单点） | Phase4 | 🟡 |

### 3.6 运维域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| M-01 | **无 CI/CD**：无流水线、无自动化构建/测试/部署 | E11 | 🔴 |
| M-02 | **无环境分离配置**：dev/test/prod 共用 application.yml，无 profile 级密钥管理 | E12 | 🟠 |
| M-03 | **无优雅下线/连接排空**：停止脚本直接 kill，50 万连接场景下会瞬间断连全部设备，不满足滚动发布 | 脚本代码 | 🟠 |
| M-04 | 无灰度、版本管理、回滚方案 | E11 | 🟡 |
| M-05 | **文档与实现不一致**：README 技术栈声明 OpenFeign，但代码 grep `@FeignClient`/`EnableFeignClients` 零命中；服务间实际走网关 `lb://` + Kafka 事件总线。需二选一：补真实 Feign 调用示例，或从技术栈表移除 | 本次核查 | 🟡 |

### 3.7 前端域

| ID | 缺陷 | 证据 | 严重度 |
|---|---|---|---|
| F-01 | 无登录态与路由守卫，刷新/直达任意页面均可 | E2 | 🔴 |
| F-02 | WebSocket（告警推送）无鉴权握手，可被第三方订阅 | E2 | 🟠 |
| F-03 | 错误处理/加载态覆盖不全，无 401 统一跳转（依赖后端鉴权落地） | 前端代码 | 🟡 |

---

## 4. 已做对的部分（不属于缺陷，客观保留）

1. **Broker 认证 fail-closed**：Redis/MySQL 异常一律拒绝新连接、保活旧会话，语义正确且有演练脚本覆盖（03/04）。
2. **幂等设计有层次**：每消费边界独立 SETNX 命名空间 + TDengine 天然覆盖 + DLQ 毒丸防护，思路为生产级。
3. **SDK 自动重连**：指数退避 + 上限，25/25 测试含踢线重连场景。
4. **工程可运行**：端口唯一性校验、11 个 jar 产物、start/stop/status 脚本、就绪轮询齐全。

---

## 5. 生产化路线图（P0 / P1 / P2）

每项含验收标准，可转 Task 追踪。

### P0 — 必须先做（上线门槛）

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P0-1 | 网关鉴权落地：引入 JWT（如 jjwt），`GlobalAuthFilter` 验签 + 过期 + `x-user-token` 携带用户身份；登录接口签发 token | 无有效 token 请求返回 401；伪造/过期 token 被拒；单元测试覆盖 |
| P0-2 | 前端登录 + 路由守卫 + 401 统一处理；WS 握手携带 token 并服务端校验 | 未登录跳登录页；登出后刷新失效；WS 拒绝未授权握手 |
| P0-3 | MQTT TLS：broker 支持 8883（Netty SslContext），SDK 支持 wss/tls 连接；演示/生产可切换 | `openssl s_client` 验证握手；SDK TLS 用例通过 | ✅ 已落地：见 §5.4（8883 双监听 + SDK TLS + openssl 握手 OK + stress connect --tls 通过） |
| P0-4 | 密钥外置：数据库/Nacos/Redis 口令从环境变量或配置中心读取；Nacos 开认证 | 仓库内无明文口令（grep 通过）；生产配置可独立注入 | ✅ 已落地：见 §5.5（Nacos 配置中心 + 客户端凭据；11/11 注册 healthy；登录/受保护端点 200、伪造 token 401；全仓 grep 零明文） |
| P0-5 | 首轮真实压测：本机起单机 Kafka/MySQL/TDengine，跑通 `stress throughput` 与演练 05，产出首组吞吐与 P99 基线 | 拿到实测峰值 + 瓶颈分析报告；P99 与当前实现对齐 |

### P1 — 上线前应做

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P1-1 | 定时任务分布式锁：CommandTimeoutScanner / TsdbFlushScheduler / AlarmService 接入 Redis 锁（SETNX+TTL 或 Redisson） | 双实例同时运行，指令/告警不重复 |
| P1-2 | 数据归档与保留策略：影子历史/指令历史/告警/上下线记录按月分区 + 归档清理任务；`iot-raw` 滚动清理 | 保留策略可配置；归档任务有测试；磁盘占用曲线收敛 |
| P1-3 | 指标管线：引入 Micrometer，暴露 Prometheus 端点（业务 + JVM + Kafka 消费 LAG）；接大盘 | `/actuator/prometheus` 可采集；LAG/吞吐/耗时指标齐全 |
| P1-4 | 日志进 ES：Filebeat/Logstash 或 logback 直接投递，对齐 Phase 2 device_log/operator_log 索引 | 日志可检索；日志与告警联动 |
| P1-5 | 链路追踪：接入 Micrometer Tracing（或 Sleuth）跨服务 TraceId | 跨 3 服务调用可查完整 Trace |

### P2 — 持续改进

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P2-1 | CI/CD：GitHub Actions/Jenkins 流水线（build → test → 打包 → 镜像 → 部署） | 提交触发全量构建；测试失败阻断 |
| P2-2 | 优雅下线：Broker 停机前连接排空（广播 CLOSE + 等 in-flight 清空）再 kill | 滚动发布设备断连数 ≤ 阈值 |
| P2-3 | 备份/恢复演练：MySQL 全量+binlog 备份、恢复演练脚本 | RTO/RPO 达标且有文档 |
| P2-4 | IP 维度限流 + 网关级安全头 | 认证端点单 IP 限流生效 |
| P2-5 | 中间件集群化专项（本报告范围外，另立文档） | 独立评估 |

---

## 5.1 P0-1 落地记录（网关鉴权）

> 状态：✅ 已实现并测试通过（2026-08-06）。对应缺陷 S-01 关闭。

**新增模块 `energy-security-core`**（纯 Java + jjwt 0.12.6，无任何 Spring/Web 依赖）：
- `JwtProperties`（secret/expireSeconds/issuer，各服务 `@ConfigurationProperties` 绑定）
- `JwtClaims`（userId/username/tenantId/enterpriseId/realName）
- `JwtTokenUtil`（HS256 签名/验签；secret ≥32 字节校验；过期→EXPIRED、篡改/错密钥/错 issuer→INVALID）
- `JwtConstants`（声明键 + 网关透传头 `x-user-id/x-user-name/x-tenant-id/x-enterprise-id`）

**为什么放独立模块**：网关是 WebFlux（`energy-common` 含 Servlet web 会冲突），不能复用 common；
独立纯库模块让网关与业务服务共用同一套验签逻辑，杜绝两处实现漂移。

**energy-system 登录**：
- `SysUser` 实体 + `SysUserMapper`、`AuthController`（`POST /api/system/auth/login`，网关 StripPrefix 后映射 `/system/auth`）
- `AuthServiceImpl`：查库（@TableLogic 过滤）→ DelegatingPasswordEncoder 密码比对（兼容 `{noop}`/`{bcrypt}`）→ 状态校验 → 更新最后登录时间 → 签发 JWT
- 安全语义：用户名/密码错误统一提示（防账号枚举）；失败记 WARN 日志
- Flyway `V2__upgrade_admin_password.sql`：种子 admin 密码 `{noop}admin123` → `{bcrypt}` 哈希（明文 admin123，已生成并验证）

**energy-gateway 验签**：
- `GlobalAuthFilter` 重写：白名单（`/api/system/auth`、`/api/system/captcha`、`/actuator`）放行；其余请求 jjwt 验签 + issuer/过期校验；通过后注入 `x-user-*` 身份头透传下游；过期→40101、缺失/伪造→40100

**测试（23 个新用例全绿）**：
- `JwtTokenUtilTest` 9：签名往返、过期、篡改、错密钥、错 issuer、缺声明、密钥长度
- `GlobalAuthFilterTest` 7：白名单放行、有效 token 透传身份头、缺/伪/过期 token 拒 401
- `AuthServiceImplTest` 7：登录成功签发+更新最后登录时间、错密码/不存在/禁用、参数缺失、BCrypt 存量密码

**遗留（不阻塞 P0-1）**：验证码（`/api/system/captcha` 白名单占位）、登录失败熔断/IP 限流（P0-4/S-05）。

> **已关闭**：原「RBAC 权限点校验」遗留项已随 §5.2 落地（Spring Security RBAC + Redis 会话令牌 + 用户/角色/菜单/单位四大管理模块）。

---

## 5.2 P0-1 深化：Spring Security RBAC + Redis 会话令牌 + 基础管理模块

> 状态：✅ 已实现并测试通过（2026-08-06）。在 §5.1 网关验签之上完成认证引擎升级与四大基础管理模块；
> 对应缺陷 S-01 彻底闭环（验签 + 权限点校验）。技术栈选型锁定 **Spring Security**（面向后续第三方登录接 Spring OAuth2）。

**设计定稿（B 方案 = RuoYi-Vue TokenService 模式）**
- JWT（jjwt 0.12.6，**HS 系列**，算法由密钥长度自动选型——dev secret 48 字节=384bit → HS384）仅承载身份 claims + 会话 uuid `sid`；
- 完整 `LoginUser`（含 permissions Set，`implements UserDetails` 便于 Jackson 反序列化）序列化至 Redis `auth:login_token:{sid}`；
- `JwtAuthenticationTokenFilter` 每请求 `sid → Redis → SecurityContext → @ss.hasPermi`；**登出删键即吊销**，角色/权限变更实时刷新在线会话（`refreshPermissionByRoleCode` / `refreshUserSessions` / `refreshAllSessions` / `revokeUserSessions`）；
- 网关 `GlobalAuthFilter` 保持 §5.1 原样（jjwt 验签 + 过期 + `x-user-*` 透传，已测，**未改动**）。

**Spring Security 认证栈**（`spring-boot-starter-security`，STATELESS）
- `@EnableMethodSecurity` + SecurityFilterChain（csrf 关、白名单 `/system/auth/**`、`/actuator/**`，其余认证）；`DaoAuthenticationProvider` + `UserDetailsService` + `DelegatingPasswordEncoder`（兼容 `{noop}`/`{bcrypt}`，`hideUserNotFoundExceptions=true` 防账号枚举）；
- 多租户登录 principal = `tenantId:username` 复合串（sys_user 唯一键 `(tenant_id, username)`）；
- 错误语义：BadCredentials→401 用户名或密码错误；Disabled→403 账号已被禁用；Locked→403 账号已被锁定；方法级 `@PreAuthorize` 拒绝由 SecurityExceptionAdvice 统一返回 `40300 无权限访问`。

**RBAC 数据模型**（对齐若依 sys_menu 语义；`@Service("ss")` PermissionService 提供 `hasPermi`）
- 四张 RBAC 表：sys_user / sys_role / sys_permission / sys_user_role / sys_role_permission；权限标识 `system:user:list` 风格；`*:*:*` 为超管通配；
- Flyway `V3__menu_columns_and_seed.sql`：sys_permission 补菜单展示字段（icon/component/visible/status/remark/update_time，**INFORMATION_SCHEMA 幂等加列**，兼容种子库/空库/重放三条路径）+ 20 行系统菜单种子（用户/角色/菜单/单位，perm_type：1 目录菜单 2 按钮）；
- 菜单表沿用 sys_permission；单位管理复用 sys_enterprise（parent_id + path + level 组织树）。

**四大管理模块**（energy-system，`/system/*` 前缀，网关 StripPrefix 后对齐）
- 用户管理 `/system/user`：分页/详情/新增/编辑/删除/状态/重置密码/分配角色（`system:user:*`）；
- 角色管理 `/system/role`：分页/列表/新增/编辑/删除/状态/分配权限（`system:role:*`）；
- 菜单资源 `/system/perm`：树/详情/新增/编辑/删除/状态（`system:perm:*`）；
- 单位管理 `/system/enterprise`：树 CRUD（`system:enterprise:*`）。

**测试与验证**
- 单元测试 **67 全绿**（AuthService/TokenService/四模块 Service；含 MyBatis-Plus 3.5.7 兼容修复、RedisUtils `keys` 返回 Set 修正）；
- 全量多模块构建 `mvn package` 通过（含 energy-security-core / energy-gateway 用例）；
- **真实库冒烟**（本机 MySQL 8.4 + Redis）：Flyway V1~V3 实跑成功（`flyway_schema_history` 三版本 success=1）；登录返回 token + `permissions:["*:*:*"]` + `roles:["SUPER_ADMIN"]`；受限用户登录 permissions 精确装配（非通配）、越权接口返回 `40300`；登出后旧 token 返回 401（会话已吊销）。

**对第三方登录（Spring OAuth2）的衔接**：业务鉴权统一走 `@ss.hasPermi` / `@PreAuthorize` 层；后续接第三方登录仅替换 token 基础设施（JWT + Redis 会话）为 OAuth2 令牌体系，业务权限点零改动。

---

## 5.3 P0-2 落地记录（前端登录 + 路由守卫 + WS 鉴权）

对应缺陷 **S-02 / F-01 / F-02**，验收口径见 §5 P0-2 行：未登录跳登录页；登出后刷新失效；WS 拒绝未授权握手。

**前端（Vue3 + Pinia + vue-router）**
- `utils/auth-storage.ts`：localStorage 持久化 `energyx_token` / `energyx_user`（http 与 store 共用，规避循环依赖）；
- `api/auth.ts` + `stores/auth.ts`：登录写内存+持久化、登出先吊销 Redis 会话再清本地（`finally` 保证本地必清）、`restoreFromStorage` 启动恢复、`isAuthenticated` getter；
- `api/http.ts`：请求拦截器自动附加 `Authorization: Bearer <token>`；**只认 HTTP 401**（登录失败是 200+业务码，不误触）→ 清本地 + `onUnauthorized` 回调跳登录页（`main.ts` 注册，携带 `?redirect=` 回跳）；
- `views/Login.vue`：表单 + 回车提交 + 错误提示 + 演示账号 admin/admin123；登录成功重建告警 WS（登出已 close，App 不重挂载）；
- `router/index.ts`：`/login`（`meta.public`）+ `beforeEach` 守卫（纯函数 `resolveAuthRedirect`，便于单测）；
- `layouts/MainLayout.vue`：右上角显示登录用户 realName + 退出登录；
- `ws/alarmSocket.ts`：URL 追加 `?token=<jwt>`（浏览器原生 WS 不能设头）；**认证拒绝判定**——从未 `onopen` 即 `onclose`（1006）→ 停止自动重连，避免带失效 token 死循环。

**后端**
- `GlobalAuthFilter` 白名单追加 `/ws`（唯一改动一行，REST 路径仍全量验签；WS 验签下沉到 alarm，直连 alarm 绕过网关同样被拒——防御纵深）；
- `energy-alarm` 新增 `WsAuthInterceptor`（`HandshakeInterceptor`，读 `?token=` 参数 → `JwtTokenUtil.parse` 验签；成功把 userId/username/tenantId 写入 attributes，失败 401 拒绝握手）+ `config/JwtConfig.java`（绑定 `energyx.jwt.*`）+ `AlarmWebSocketConfig.addInterceptors` + `AlarmWebSocketHandler` 记录登录身份。

**验证证据**
- 前端：`npm run test` **48 用例全绿**（新增 auth store / http 拦截器 / 路由守卫 / alarmSocket 认证 4 个 spec，既有 25 用例零回归）；`npm run build`（vue-tsc + vite）通过；
- 后端：`mvn test -pl energy-alarm,energy-gateway -am` **BUILD SUCCESS**（新增 WsAuthInterceptorTest 4 例；gateway 7 例 / security-core 10 例零回归）；
- **端到端**（gateway:8000 + system:8101 + alarm:8115，Nacos 已注册）：无 token REST → 401；带 token → 200；登出后旧 token 访问 system → 401（Redis 会话已吊销）；WS 握手直连 alarm——合法 token → 101、无 token → 401、伪造 token → 401；经网关升级 → 合法 token 连接建立（日志 `userId=1 tenantId=1`）、无/伪造 token 在 alarm 侧被拒（日志 `WS 握手拒绝：缺少 token` / `reason=INVALID`）。

**已知边界（记录，非阻塞）**
- **网关 WS 代理对下游 401 的可见性**：WebFlux 服务端先向客户端提交 101、再回调 handler 连接下游；下游拒绝时客户端已收到 101 随后连接立即关闭。前端 `alarmSocket` 的「未 open 即 close → 停止重连」恰好覆盖该形态，交互正确；HTTP 状态码不反映到客户端属框架固有行为。
- **业务服务 REST 会话时效**：网关为无状态 JWT 验签，energy-system 是唯一会话权威（Redis `auth:login_token:{sid}`）。登出后旧 token 访问 alarm/shadow/command 等业务 REST 仍能通过（JWT 未过期），仅 energy-system 拒绝。P0-2 验收「登出后刷新失效」由前端清 token 满足；业务面全量吊销需 P1 评估网关级会话校验（与当前无状态网关设计相悖，故列为已知取舍）。
- **附带修复**：energy-alarm 规则缓存 SQL 中 `condition` 为 MySQL 保留字未加反引号，导致启动期缓存加载持续失败（此前被异常兜底掩盖）。已改为 `` `condition` ``，重启后缓存正常刷新。

---

## 5.4 P0-3 落地记录（MQTT TLS 8883）

对应缺陷 **S-03**。验收口径：`openssl s_client` 验证握手；SDK TLS 用例通过；演示/生产可切换。**「wss/tls」按 mqtts（TLS over TCP 8883）落地**——验收验证命令是 `openssl s_client` 直连 TCP 套接字，SDK/Broker 均无 WebSocket codec，MQTT-over-WebSocket 不在本次范围。

**Broker 双监听（明文 1883 恒开 + TLS 8883 可切换）**
- `energyx.broker.tls.*` 配置（`BrokerProperties.Tls`）；`NettyServerConfig` 抽共享 acceptor 骨架 + pipeline 工厂，TLS 开启时 pipeline 头部加 `SslHandler`（先解密再走 MQTT 编解码），两个 acceptor 共享 boss/worker EventLoopGroup 与同一 `MqttChannelInboundHandler` 单例；
- 条件 Bean（`@ConditionalOnProperty(prefix="energyx.broker.tls", name="enabled", havingValue="true")`）：`brokerSslContext`（`SslContextBuilder.forServer(cert, key)`，证书缺失**启动即 fail-fast**）+ `mqttTlsServerBootstrap`；TLS 关闭时两个 Bean 均不存在 → 行为与明文单端口逐字节一致；
- `MqttBrokerServer` 双 `@Qualifier` 注入 + `ObjectProvider` 惰性获取（避免 `NoUniqueBeanDefinitionException`），`start()` 依次绑定 1883 / 8883，`stop()` 双通道优雅关闭。

**证书**：`deploy/scripts/gen-mqtt-certs.sh`（`openssl genpkey` PKCS#8 + 自签，SAN `DNS:localhost, IP:127.0.0.1`）→ `deploy/certs/server-cert.pem` / `server-key.pem`（gitignored，密钥外置，与 P0-4 同轨）。

**SDK TLS 传输**：`MqttClientConfig.useTls / tlsTrustCertFile / tlsSkipVerify`；`clientSslContext()` 懒建（skipVerify→`InsecureTrustManagerFactory`；tlsTrustCertFile→固定信任锚；都无→JDK 默认信任库拒自签）；`connect()` 在 pipeline 头部加 `SslHandler`（带 peerHost + `setEndpointIdentificationAlgorithm("HTTPS")` 主机名校验）；`exceptionCaught` 以真实异常补全 pending `connAckFuture`，`connect()` 沿 cause 链识别 `SSLHandshakeException` 抛出「TLS 握手失败」而非笼统「连接被服务端拒绝」。

**Stress**：`connect --tls --tls-skip-verify | --tls-cert <server-cert.pem>`（skipVerify 优先）。

**验证输出（本机实测）**
```
openssl s_client -connect 127.0.0.1:8883 -CAfile deploy/certs/server-cert.pem -verify_return_error < /dev/null
  Verify return code: 0 (ok)；Protocol: TLSv1.3；Cipher: TLS_AES_128_GCM_SHA256
Broker 日志：MQTT 端口 1883 监听成功 / MQTT TLS 端口 8883 监听成功
SDK：Tests run: 29 全绿（新增 MqttDeviceTlsWireTest 3 例：固定证书 / skipVerify / 无信任配置失败）
Stress：--port 8883 --tls --tls-skip-verify → 成功 5 失败 0；--tls-cert 固定证书 → 成功 5 失败 0；明文 1883 回归 → 成功 3 失败 0
Fail-fast：BROKER_TLS_CERT=/nonexistent/x.pem → 启动即中止（MQTT TLS 证书缺失…）
```

**Env 注入（演示/生产可切换，start-stack.sh 零改动，env 透传）**

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BROKER_TLS_ENABLED` | `false` | 生产置 true |
| `BROKER_TLS_PORT` | `8883` | TLS 监听端口 |
| `BROKER_TLS_CERT` | `deploy/certs/server-cert.pem` | 证书链 PEM（默认相对路径按进程 CWD 解析；生产传绝对路径） |
| `BROKER_TLS_KEY` | `deploy/certs/server-key.pem` | 私钥 PEM（同上） |

**附带修复（验证中暴露的既有契约缺陷）**：平台锚点 product_key = `snd_ess_pcs`（含 `_`），而 SDK `DeviceIdentity` 此前禁 productKey 含 `_`、Broker 按 clientId **第一个** `_` 拆分——真实产品永远无法通过认证。已对齐契约：Broker 改按**最后一个** `_` 拆分（`DeviceAuthService`）；SDK 放行 productKey 含 `_`、双禁 `&`（`DeviceIdentity`，deviceName 仍禁 `_` 保证拆分无歧义）。SDK 测试 28→29，全绿。

**明确不做**：MQTT-over-WebSocket（wss 的 WS 语义，本次按 mqtts 落地；SslContext 基建传输无关，未来可复用）；mTLS 设备证书双向认证（设备认证仍 HMAC-SHA256 + nonce，TLS 仅传输加密）；TLS 版本/套件调优（JDK 默认 TLS1.3）；生产 CA 签发（脚本仅自签演示）。

---

## 5.5 P0-4 落地记录（密钥外置 + Nacos 客户端认证凭据）

> 状态：✅ 已实现并验证（2026-08-06）。对应缺陷 **S-04 / M-02 关闭**。

**方案**：密钥迁入 Nacos 配置中心（用户选定机制），服务经 `spring.config.import: nacos:energy-shared.yaml?group=ENERGY`（SCA 2023.0.1.0 config-data 导入流，非 legacy bootstrap）拉取；全部 11 个模块 `application.yml` 移除明文口令行（`spring.datasource.password` / `energyx.jwt.secret` / `energyx.tsdb.jdbc-password`），由 Nacos 注入。SCA config 客户端须显式 `spring.cloud.nacos.config.server-addr`（不回落 discovery.server-addr）。

**密钥落点**（仓库零明文，grep 通过）：

| 密钥 | 配置键 | 注入源 |
|---|---|---|
| MySQL 密码 | `spring.datasource.password` | Nacos `energy-shared.yaml`（本地经 `deploy/scripts/init-nacos-config.sh` 从 `deploy/env/local.env` 推送，group ENERGY） |
| JWT 密钥 | `energyx.jwt.secret` | 同上（system/gateway/alarm 三模块） |
| TDengine 密码 | `energyx.tsdb.jdbc-password` | 同上（tsdb） |
| Nacos 客户端凭据 | `spring.cloud.nacos.username/password` | 环境变量 `NACOS_USERNAME` / `NACOS_PASSWORD`（yml `${...}` 引用） |

**部署注入**：`deploy/env/local.env`（gitignored，含真实 dev 值；值含 `&` 须单引号）→ `deploy/env/local.env.example`（提交，全 `***`）→ `deploy/env/README.md`（用法）；`start-stack.sh` 与 `test/drill/lib.sh` 条件加载 local.env；生产经环境变量/KMS 注入同名键，不落仓库。

**Nacos 认证口径（按用户决策：保持现状 + 加客户端凭据）**：不翻转 `nacos.core.auth.enabled`（保持 false；控制台/管理 API 认证已开）；服务侧全部注入客户端凭据，面向生产开启 core auth 时即生效。

**Fail-fast 实测**：`energy-shared.yaml` 缺失/不可达 → 配置导入强制失败 → 无 `spring.datasource.password` → `Access denied for user 'root'@'localhost' (using password: NO)` → **启动中止**（服务无法在密钥缺失下运行）。注意：`NACOS_USERNAME/PASSWORD` 缺失**不**触发占位符 fail-fast（SCA config-data 阶段把未解析占位符作字面量、客户端匿名回退，因 core auth 关闭）；生产开启 core auth 后缺失凭据将导致登录失败 → 导入失败 → 启动中止。

**验证输出**：11/11 服务 Nacos 注册 healthy=1；网关登录 200（HS384 JWT 签发，secret 来自 Nacos）→ 带 token `/api/system/user/page` 200、无/伪造 token 401；全仓 grep `root&QAQ` / `energyx-ems-dev-secret-…` / `QAQ123qaq` / `taosdata` 零命中（`com.taosdata.jdbc` 为驱动坐标除外）；`stress seed --count 3` 经 env 注入通过。

**附带修复**：Java 层兜底默认值移除（`JwtProperties.secret` / `TsdbProperties.jdbcPassword` / `StressCli.MYSQL_PASSWORD` 改 env 驱动，仓库不再有明文兜底）；`WsAuthInterceptorTest` 补显式测试 secret；`docker-compose.yml` 注释内口令脱敏；P0-4 全栈启动暴露的 `energy-access` `LifecycleProcessor` bean 名冲突（`@Component("accessLifecycleProcessor")`）一并修复。

**观察（非阻塞）**：Nacos 3.1.0 客户端 gRPC「Server check fail / 9848」周期性超时日志为各服务共性噪声，不影响注册与配置拉取（11/11 healthy + 配置生效实证）；网关白名单 `/api/system/captcha` 与 SecurityConfig `/system/auth/captcha` 为历史占位（当前登录流程无验证码，`LoginRequest` 无 captcha 字段、前端零引用），Task 6 网关验证用真实登录链路替代。

---

## 6. 缺陷追踪表（Checklist）

> 建议作为 Phase 9 工作清单，逐项打勾；勾选"已修复"时须附证据（测试/演练输出）。

| 追踪 ID | 域 | 缺陷摘要 | 严重度 | 修复任务 | 状态 | 证据/备注 |
|---|---|---|---|---|---|---|
| S-01 | 安全 | 网关鉴权空壳（无验签） | 🔴 | P0-1 | ✅ 已修复 | 见 §5.1（验签）+ §5.2（RBAC 权限点校验） |
| S-02 | 安全 | 前端无登录/RBAC | 🔴 | P0-2 | ✅ 已修复 | 见 §5.3（登录/守卫/401；RBAC 权限点渲染见路线图 P1） |
| S-03 | 安全 | MQTT 无 TLS | 🔴 | P0-3 | ✅ 已修复 | 见 §5.4（8883 Netty SslContext + SDK TLS；openssl s_client 握手 OK；stress connect --tls 通过；SDK TLS 用例 3 条） |
| S-04 | 安全 | 凭据明文入库 / Nacos 无认证 | 🟠 | P0-4 | ✅ 已修复 | 见 §5.5（密钥迁 Nacos 配置中心 + 客户端凭据；全仓 grep 零明文） |
| S-05 | 安全 | 无 IP 限流 | 🟠 | P2-4 | ☐ 未开始 | 认证仅设备维度熔断 |
| C-01 | 容量 | 容量声明未实测 | 🔴 | P0-5 | ☐ 未开始 | `test/drill/logs/` 为空 |
| C-02 | 容量 | P99 无基线 | 🔴 | P0-5 | ☐ 未开始 | 演练 05 未执行 |
| D-01 | 存储 | 分库分表未接入 | 🔴 | 专项 | ☐ 未开始 | `sharding/` 仅模板 |
| D-02 | 存储 | 无归档/保留策略 | 🟠 | P1-2 | ☐ 未开始 | 业务表只增不删 |
| D-03 | 存储 | 无备份预案 | 🟠 | P2-3 | ☐ 未开始 | — |
| O-01 | 观测 | 无指标管线 | 🟠 | P1-3 | ☐ 未开始 | 无 micrometer |
| O-02 | 观测 | 无链路追踪 | 🟠 | P1-5 | ☐ 未开始 | 无 sleuth/tracing |
| O-03 | 观测 | 日志未进 ES | 🟠 | P1-4 | ☐ 未开始 | 仅告警入 ES |
| R-01 | 可靠性 | @Scheduled 无分布式锁 | 🟠 | P1-1 | ☐ 未开始 | 4 处定时任务 |
| R-02 | 可靠性 | 上行 QoS0 丢失窗口 | 🟠 | P1-6* | ☐ 未开始 | SDK 属性/事件 QoS0 |
| R-03 | 可靠性 | 去重缺 messageId 退化 | 🟠 | P1-7* | ☐ 未开始 | 雪花回退非幂等 |
| M-01 | 运维 | 无 CI/CD | 🔴 | P2-1 | ☐ 未开始 | 无流水线 |
| M-05 | 运维 | README 声明 OpenFeign 但代码零引用（文档与实现不一致） | 🟡 | 专项 | ☐ 未开始 | grep 零命中 |
| M-02 | 运维 | 无环境分离配置 | 🟠 | P0-4 | ✅ 已修复 | 见 §5.5（deploy/env/local.env + Nacos energy-shared.yaml 独立注入） |
| M-03 | 运维 | 无优雅下线/排空 | 🟠 | P2-2 | ☐ 未开始 | stop 脚本直接 kill |
| F-01 | 前端 | 无登录态/路由守卫 | 🔴 | P0-2 | ✅ 已修复 | 见 §5.3（登录页 + 守卫 + 401 统一处理 + 登出） |
| F-02 | 前端 | WS 无鉴权握手 | 🟠 | P0-2 | ✅ 已修复 | 见 §5.3（`?token=` + alarm WsAuthInterceptor 401 拒握手） |

> *P1-6/P1-7 为路线图外新增项：若生产严格要求设备面 at-least-once，需评估 SDK 上行 QoS1 或设备侧本地缓存重发。

---

## 7. 评审清单（对照 §3 域覆盖）

- [ ] 安全：鉴权 / RBAC / TLS / 密钥 / 限流 全部闭环
- [ ] 容量：至少一组实测吞吐 + P99 基线 + 瓶颈分析
- [ ] 存储：归档保留策略生效、备份可恢复、分库分表专项完成
- [ ] 观测：指标 / 追踪 / 日志检索 / 告警大盘 可用
- [ ] 可靠性：定时任务去锁、上行丢失窗口消除、去重幂等闭环
- [ ] 运维：CI/CD、环境分离、优雅下线、回滚 落地
- [ ] 前端：登录态、守卫、WS 鉴权、401 处理 齐全
- [ ] 全链路回归：演练 01~05 全部 PASS 且日志留存
