# P0-4 密钥外置至 Nacos 配置中心 + 客户端认证 — 设计

- 日期：2026-08-06
- 状态：已获用户批准（2026-08-06）
- 对应缺陷：S-04「凭据明文入库 / Nacos 无认证」、M-02「无环境分离配置」
- 验收口径（Phase9 §5 P0-4）：仓库内无明文口令（grep 通过）；生产配置可独立注入；Nacos 开认证

## 1. 背景与现状（侦察实证）

**明文口令清单（11 个模块）**：

| 密钥 | 值 | 位置 |
|---|---|---|
| MySQL 密码 | `${MYSQL_PASSWORD}` | energy-system / product / device / station / mqtt-broker / access / shadow / command / alarm（9 个模块的 `spring.datasource.password`） |
| JWT 密钥 | `${JWT_SECRET}` | energy-system / gateway / alarm（3 个模块的 `sanduo.jwt.secret`） |
| TDengine 密码 | `${TDENGINE_PASSWORD}` | energy-tsdb（`sanduo.tsdb.jdbc-password`） |

**Nacos 服务器现状**（本机 `D:\Program Files\nacos-server-3.1.0`，standalone）：
- `nacos.core.auth.console.enabled=true` + `nacos.core.auth.admin.enabled=true` → 控制台/管理 API 已认证（实测 `POST /v1/auth/users/login` 需凭据，`nacos/${NACOS_PASSWORD}` 返回 accessToken）。
- `nacos.core.auth.enabled=false` → config/discovery API 未强制认证；当前全部服务**匿名注册**（yml 无 `spring.cloud.nacos.username/password`）。
- 服务注册组 `ENERGY`、namespace `public`。

## 2. 设计决策（用户已确认）

1. **密钥外置机制 = Nacos 配置中心**：密钥字面值放入 Nacos dataId `energy-shared.yaml`（group `ENERGY`，namespace `public`），各服务经 `spring.config.import` 拉取。验收「口令从配置中心读取」字面满足。
2. **Nacos 认证推进 = 保持现状 + 客户端凭据**：服务端不改（console/admin 已认证）；客户端全部注入 `NACOS_USERNAME/NACOS_PASSWORD` 连接 Nacos（forward-looking，prod 开启强制认证后无需改代码）。服务端 `nacos.core.auth.enabled=true` 的开启步骤写入文档，本次不执行（避免全栈重启风险）。
3. **只外置密钥，不做配置大迁移**：非密钥配置（datasource url、redis host/port、kafka、mybatis、nacos discovery 等）留在各服务本地 `application.yml`。

## 3. 架构与组件

### 3.1 密钥数据流

```
部署者                    服务进程
  │ local.env                │
  │ (env: NACOS_* /          │ 读取 application.yml
  │  MYSQL_PASSWORD/…)        │   spring.config.import: nacos:energy-shared.yaml?group=ENERGY
  ▼                          │   spring.cloud.nacos.username/password ← ${NACOS_USERNAME}/${NACOS_PASSWORD}
init-nacos-config.sh         ▼
  │ 登录 Nacos 取 accessToken  spring 解析 env → 带凭据连 Nacos → 拉取 dataId
  │ POST /v1/cs/configs        │
  │ dataId=energy-shared.yaml ▼
  ▼                       绑定 spring.datasource.password / sanduo.jwt.secret / sanduo.tsdb.jdbc-password
Nacos dataId（密钥）───────────────────────────────┐
```

### 3.2 新增/修改组件

| # | 组件 | 类型 | 职责 |
|---|---|---|---|
| 1 | `deploy/scripts/init-nacos-config.sh` | NEW 脚本 | source local.env → 登录 Nacos 取 accessToken → 组装 YAML content（值由 env 注入）→ POST 推送 dataId `energy-shared.yaml`（group=ENERGY, type=yaml）。幂等（可重复执行覆盖）。 |
| 2 | `deploy/env/local.env` | NEW（gitignored） | dev 密钥值：`NACOS_USERNAME`、`NACOS_PASSWORD`、`MYSQL_PASSWORD`、`JWT_SECRET`、`TDENGINE_PASSWORD`（bash `export`，值加单引号防 `&` 解释）。 |
| 3 | `deploy/env/local.env.example` | NEW（提交） | 同键名、值全 `***`，供新环境复制。 |
| 4 | `deploy/env/README.md` | NEW | 说明 local.env 用途、init 脚本用法、生产注入方式。 |
| 5 | parent `backend/pom.xml` | EDIT | `<dependencies>` 加 `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config`（BOM 2023.0.1.0 管版本）→ 11 模块全继承。 |
| 6 | 11 个模块 `application.yml` | EDIT | 删密钥字面值行；加 `spring.config.import: nacos:energy-shared.yaml?group=ENERGY` + `spring.cloud.nacos.username: ${NACOS_USERNAME}` / `password: ${NACOS_PASSWORD}`。 |
| 7 | `deploy/scripts/start-stack.sh` | EDIT | 顶部（build 检查前）`[ -f "${ROOT}/deploy/env/local.env" ] && source "${ROOT}/deploy/env/local.env"`。 |
| 8 | `docs/design/Phase9-生产化差距分析.md` | EDIT | v1.4（+§5.5 P0-4 落地记录）；S-04/M-02 行标 `✅ 已修复`；P0-4 roadmap 行标已落地；附 prod Nacos 强制认证开启步骤。 |

### 3.3 dataId `energy-shared.yaml` 内容模板

```yaml
spring:
  datasource:
    password: ${MYSQL_PASSWORD}
sanduo:
  jwt:
    secret: ${JWT_SECRET}
  tsdb:
    jdbc-password: ${TDENGINE_PASSWORD}
```

> init 脚本把 `${MYSQL_PASSWORD}` 等占位符替换为 local.env 中的字面值后再 POST，故 Nacos 中存的是字面密钥（配置中心即密钥仓库）；脚本自身零硬编码密钥。

### 3.4 各模块 application.yml 变化（以 energy-system 为例）

删除：
```yaml
spring:
  datasource:
    password: ${MYSQL_PASSWORD}
```
新增（URL/username 等非密钥保留）：
```yaml
spring:
  config:
    import: nacos:energy-shared.yaml?group=ENERGY
  cloud:
    nacos:
      username: ${NACOS_USERNAME}
      password: ${NACOS_PASSWORD}
```
gateway 删 `sanduo.jwt.secret` 行、alarm 删 `sanduo.jwt.secret` 行、tsdb 删 `sanduo.tsdb.jdbc-password` 行，各自补 config import + nacos 凭据。

## 4. 错误处理

- `${NACOS_USERNAME}/${NACOS_PASSWORD}` **无默认值**：env 缺失 → placeholder 解析失败 → 启动即中止（fail-fast，错误明示变量名）。
- `spring.config.import` 拉取失败（Nacos 不可达 / dataId 不存在）→ 启动失败（fail-fast）。Nacos 本已是全平台硬依赖（discovery），start-stack.sh 先等 Nacos 就绪，可接受。
- init 脚本失败（登录失败 / POST 非 2xx）→ 非零退出并打印原因；脚本 `set -euo pipefail`。

## 5. 已知风险与回退

- **SCA 2023.0.1.0 的 `spring.config.import: nacos:` 连接属性可见性历史坑**：若 `${NACOS_USERNAME}` 等占位符在同文件解析不被 import resolver 看到 → 回退方案：local.env 直出 `SPRING_CLOUD_NACOS_USERNAME` / `SPRING_CLOUD_NACOS_PASSWORD` 环境变量（Spring 松绑定，import 时 env 已可用）。以实证为准，二者取一。
- **Nacos 3.1.0 服务器 + nacos-client 2.2.x（SCA 2023.0.1.0 内置）config gRPC 兼容性**：3.x 兼容 2.x 协议；以「全栈启动 + 服务注册 + 网关路由」实证为准。
- **同一 dataId 全模块共享**：tsdb/gateway 拉到无用的 `spring.datasource.password` 无副作用（tsdb 已排除 DataSource 自动配置、gateway 为 reactive 无 DataSource）；最小权限可按模块拆 dataId，列入后续优化，不做。

## 6. 明确不做

- Redis 口令外置（当前全模块 Redis 无密码，无密钥可外置；生产需要时以 `REDIS_PASSWORD` env 挂点补）。
- 全部配置迁入 Nacos（只迁密钥）。
- 服务端 `nacos.core.auth.enabled=true` 强制开启（本次仅客户端凭据 + 文档）。
- Jasypt 等加密配置（无新第三方依赖约束下不引入）。

## 7. 验证（Git Bash，仓库根；`M2=/d/Program Files/maven-repo`）

1. **准备 local.env**：复制 `.example` → 填 dev 值（`${MYSQL_PASSWORD}` / dev JWT secret / `${TDENGINE_PASSWORD}` / `nacos` / `${NACOS_PASSWORD}`）。
2. **init 推送**：`bash deploy/scripts/init-nacos-config.sh` → 退出码 0；随后 `curl` 带 accessToken `GET /nacos/v1/cs/configs?dataId=energy-shared.yaml&group=ENERGY` 断言内容含三密钥。
3. **仓库零明文**：`git grep -n -E "${MYSQL_PASSWORD}|${TDENGINE_PASSWORD}|${JWT_SECRET}"` → 零命中（仅跟踪文件）。
4. **构建**：`cd backend && mvn -Dmaven.repo.local="$M2" package -DskipTests`（全模块，含新依赖）。
5. **全栈**：`./deploy/scripts/start-stack.sh --skip-infra --skip-build` → 11 服务就绪；Nacos 控制台见全部实例（证明 NACOS_* 凭据生效）；`curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/api/system/captcha` = 200（证明网关→energy-system 路由通 → MySQL 密码经 Nacos 解析生效，Flyway 迁移已跑）。
6. **负例 fail-fast**：临时去掉 local.env 启 energy-system → 启动中止并明示缺 `NACOS_PASSWORD`。
