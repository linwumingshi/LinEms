# P0-4 密钥外置至 Nacos 配置中心 + 客户端认证 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 11 个后端服务的明文口令（MySQL `${MYSQL_PASSWORD}` / JWT dev secret / TDengine `${TDENGINE_PASSWORD}`）从 application.yml 迁入 Nacos 配置中心 dataId `energy-shared.yaml`，并给全部服务加 Nacos 客户端认证凭据，使仓库零明文口令且生产可独立注入。

**Architecture:** 各服务 `spring.config.import: nacos:energy-shared.yaml?group=ENERGY` 拉取共享密钥；Nacos 登录凭据经 env `NACOS_USERNAME/NACOS_PASSWORD` 注入（缺失 fail-fast）；`deploy/scripts/init-nacos-config.sh` 从 gitignored `deploy/env/local.env` 读取密钥值、登录 Nacos 后推送 dataId（幂等覆盖）。服务端 Nacos 认证保持现状（console/admin 已认证），仅客户端带凭据。

**Tech Stack:** Spring Cloud Alibaba 2023.0.1.0（`spring-cloud-starter-alibaba-nacos-config`，BOM 管版本）、Nacos 3.1.0（127.0.0.1:8848，group ENERGY，namespace public，凭据 nacos/${NACOS_PASSWORD}）、Bash、Maven。

## Global Constraints

- Maven 一律 `-Dmaven.repo.local="/d/Program Files/maven-repo"`；跨模块构建用 `-pl <module> -am`。
- 本仓库**无 git**（`Is a git repository: false`）→ 每个任务的「提交」步骤替换为「验证」步骤；`git grep` 用 `grep -r` 替代（验收口径按跟踪文件口径，即排除 `deploy/env/` 目录）。
- 除 `spring-cloud-starter-alibaba-nacos-config` 外不引入任何新第三方依赖；版本由 `spring-cloud-alibaba-dependencies` BOM（2023.0.1.0）管理，不写 `<version>`。
- Bash env 文件中 `${MYSQL_PASSWORD}` 等含 `&` 的值必须**单引号**包裹（防 bash 后台符解释）。
- Nacos 连接凭据占位符 `${NACOS_USERNAME}/${NACOS_PASSWORD}` **不设默认值** → env 缺失即启动 fail-fast。
- 网关 `GlobalAuthFilter` 禁止改动。
- 无伪代码/空方法/TODO。
- 已知坑（写入本地记忆）：`&` 出现在 yml 明文口令、env 单引号；`$!`/`ps`/`kill` 对 java.exe 无效，起停 Java 用 PowerShell `Start-Process`/`Stop-Process`。

---

### Task 1: 密钥区 `deploy/env/` + `.gitignore` 规则

**Files:**
- Modify: `.gitignore`
- Create: `deploy/env/local.env`
- Create: `deploy/env/local.env.example`
- Create: `deploy/env/README.md`

**Interfaces:**
- Produces: `deploy/env/local.env`（含 5 个 export：`NACOS_USERNAME`、`NACOS_PASSWORD`、`MYSQL_PASSWORD`、`JWT_SECRET`、`TDENGINE_PASSWORD`）——Task 2 的 init 脚本与 Task 5 的 start-stack.sh 依赖它。

- [ ] **Step 1: `.gitignore` 追加**

在现有 6 行后追加（保持密钥一律不入库的口径）：
```gitignore
# P0-4：本地密钥 env（dev 值仅在本机，不入库；.example 为提交的模板）
deploy/env/local.env
deploy/env/*.tmp
```

- [ ] **Step 2: 创建 `deploy/env/local.env`（gitignored，dev 值）**

```bash
# 本地 dev 密钥值 —— 本文件不入库（gitignore 已排除）。生产用独立环境变量注入，勿改此文件语义。
export NACOS_USERNAME='nacos'
export NACOS_PASSWORD='${NACOS_PASSWORD}'
export MYSQL_PASSWORD='${MYSQL_PASSWORD}'
export JWT_SECRET='${JWT_SECRET}'
export TDENGINE_PASSWORD='${TDENGINE_PASSWORD}'
```

- [ ] **Step 3: 创建 `deploy/env/local.env.example`（提交，值全掩码）**

```bash
# 复制为 local.env 并填入本环境真实值。所有值均视为密钥，勿提交真实值。
export NACOS_USERNAME='***'
export NACOS_PASSWORD='***'
export MYSQL_PASSWORD='***'
export JWT_SECRET='***'
export TDENGINE_PASSWORD='***'
```

- [ ] **Step 4: 创建 `deploy/env/README.md`**

```markdown
# deploy/env — 本地密钥注入

- `local.env`（gitignored）：本机 dev 密钥值。`start-stack.sh` 启动时自动 source；
  `init-nacos-config.sh` 读取它推送 Nacos dataId。
- `local.env.example`（提交）：键名模板，值全 `***`。新环境 `cp local.env.example local.env` 后填值。
- 生产：不依赖本文件，用部署平台/CI 注入同名环境变量即可（服务从 env 读 NACOS_USERNAME/NACOS_PASSWORD，
  其它密钥经 Nacos 配置中心读取——生产 Nacos 需开启强制认证，见 Phase9 §5.5）。

注意：值含 `&`（如 ${MYSQL_PASSWORD}）必须单引号包裹，否则被 bash 当作后台符。
```

- [ ] **Step 5: 验证**

```bash
bash -c 'source "deploy/env/local.env" && printf "%s|%s\n" "$MYSQL_PASSWORD" "$NACOS_PASSWORD"'
```
Expected: `${MYSQL_PASSWORD}|${NACOS_PASSWORD}`（证明单引号防 `&` 解释生效）。再确认 `.gitignore` 含 `deploy/env/local.env`、`deploy/env/local.env.example` 存在且值全 `***`。

---

### Task 2: `init-nacos-config.sh` 推送脚本

**Files:**
- Create: `deploy/scripts/init-nacos-config.sh`

**Interfaces:**
- Consumes: `deploy/env/local.env`（Task 1）；Nacos 登录 API `/v1/auth/users/login`、配置发布 API `/v1/cs/configs`。
- Produces: Nacos dataId `energy-shared.yaml`（group ENERGY，type yaml，内容含三密钥字面值）；退出码 0=成功。

- [ ] **Step 1: 写脚本**（Git Bash；`set -euo pipefail`）

```bash
#!/usr/bin/env bash
# =====================================================================
# 初始化 Nacos 配置中心密钥 dataId：energy-shared.yaml（group ENERGY）
# 读取 deploy/env/local.env 的密钥值 → 组装 YAML → 登录 Nacos 取 accessToken → 推送（幂等覆盖）
# 用法：bash deploy/scripts/init-nacos-config.sh
# =====================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROOT}/deploy/env/local.env"

# 1. 加载密钥值（缺失即 fail-fast）
[ -f "$ENV_FILE" ] || { echo "[init-nacos] 缺少 $ENV_FILE（先 cp local.env.example local.env 并填值）" >&2; exit 1; }
source "$ENV_FILE"

NACOS_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_GROUP="${NACOS_GROUP:-ENERGY}"
DATA_ID="energy-shared.yaml"

# 2. 登录取 accessToken
TOKEN="$(curl -s -m 10 -X POST "http://${NACOS_ADDR}/nacos/v1/auth/users/login" \
  -d "username=${NACOS_USERNAME}&password=${NACOS_PASSWORD}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
[ -n "$TOKEN" ] || { echo "[init-nacos] Nacos 登录失败（检查 NACOS_USERNAME/NACOS_PASSWORD）" >&2; exit 1; }

# 3. 组装 content（值由 env 展开注入 heredoc，脚本零硬编码密钥）
CONTENT_FILE="$(mktemp)"
trap 'rm -f "$CONTENT_FILE"' EXIT
cat > "$CONTENT_FILE" <<EOF
# P0-4 密钥 dataId（由 init-nacos-config.sh 推送，勿手工改动）
spring:
  datasource:
    password: ${MYSQL_PASSWORD}
sanduo:
  jwt:
    secret: ${JWT_SECRET}
  tsdb:
    jdbc-password: ${TDENGINE_PASSWORD}
EOF

# 4. 推送（--data-urlencode content@file 自动 url-encode 多行内容）
HTTP_CODE="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST \
  "http://${NACOS_ADDR}/nacos/v1/cs/configs?accessToken=${TOKEN}" \
  --data-urlencode "dataId=${DATA_ID}" \
  --data-urlencode "group=${NACOS_GROUP}" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content@${CONTENT_FILE}")"
[ "$HTTP_CODE" = "200" ] || { echo "[init-nacos] 推送失败 HTTP $HTTP_CODE" >&2; exit 1; }
echo "[init-nacos] 已推送 ${DATA_ID} (group=${NACOS_GROUP})，内容含 3 个密钥"
```

- [ ] **Step 2: 运行并回读验证**

```bash
bash deploy/scripts/init-nacos-config.sh
TOKEN="$(curl -s -m 10 -X POST "http://127.0.0.1:8848/nacos/v1/auth/users/login" -d "username=nacos&password=${NACOS_PASSWORD}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
curl -s -m 10 "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=energy-shared.yaml&group=ENERGY&accessToken=${TOKEN}"
```
Expected: 退出码 0；回读内容含 `password: ${MYSQL_PASSWORD}`、`secret: ${JWT_SECRET}`、`jdbc-password: ${TDENGINE_PASSWORD}`。

---

### Task 3: parent pom 继承 Nacos 配置中心依赖

**Files:**
- Modify: `backend/pom.xml`（在 `</dependencyManagement>` 后、`<build>` 前插入 `<dependencies>` 块）

**Interfaces:**
- Produces: 全部子模块（含 energy-common/energy-security-core 两个纯库）classpath 含 `spring-cloud-starter-alibaba-nacos-config`；版本由 BOM 2023.0.1.0 管理。

- [ ] **Step 1: 插入依赖块**

```xml
  <dependencies>
    <!-- P0-4 密钥外置：全部模块继承 Nacos 配置中心客户端（BOM 2023.0.1.0 管版本；纯库模块不触发自动配置） -->
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>
  </dependencies>
```

- [ ] **Step 2: 验证依赖可达**

```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/backend"
mvn -Dmaven.repo.local="/d/Program Files/maven-repo" -pl energy-common dependency:tree -Dincludes=com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config -q
```
Expected: 树中含 `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config:jar:2023.0.1.0`。

---

### Task 4: 11 个模块 `application.yml` 密钥外置改造

**Files:**
- Modify: 全部 11 个 `backend/*/src/main/resources/application.yml`
  - energy-system、energy-product、energy-device、energy-station、energy-mqtt-broker、energy-access、energy-shadow、energy-command、energy-alarm（9 个：删 datasource `password: ${MYSQL_PASSWORD}`）
  - energy-gateway、energy-alarm（2 个：另删 `sanduo.jwt.secret`）
  - energy-tsdb（1 个：删 `sanduo.tsdb.jdbc-password`）

**Interfaces:**
- Consumes: Task 2 的 dataId（服务启动时经 `spring.config.import` 拉取）；env `NACOS_USERNAME/NACOS_PASSWORD`。
- Produces: 11 个模块均含 `spring.config.import: nacos:energy-shared.yaml?group=ENERGY` + `spring.cloud.nacos.username/password` + `spring.cloud.nacos.config.server-addr`；仓库 yml 零明文口令。

- [ ] **Step 1: 9 个 datasource 模块统一变换**（system/product/device/station/broker/access/shadow/command/alarm）

对每个文件做**三处**编辑（以 energy-command 为范式，其余同构）：

编辑 A —— 删 datasource 密码行：
```yaml
    username: root
    password: ${MYSQL_PASSWORD}            # ← 删除此行
    hikari:
```

编辑 B —— `spring:` 下新增 `config.import`（放在 `application:` 之后）：
```yaml
  application:
    name: energy-command
  config:
    import: nacos:energy-shared.yaml?group=ENERGY
```

编辑 C —— `spring.cloud.nacos:` 下新增认证凭据 + config 地址：
```yaml
  cloud:
    nacos:
      username: ${NACOS_USERNAME}
      password: ${NACOS_PASSWORD}
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: public
        group: ENERGY
      config:
        server-addr: 127.0.0.1:8848
        namespace: public
```
（`discovery:` 段为各文件原样保留；`config.server-addr` 必填——SCA 的 config 客户端不回退到 `discovery.server-addr`。）

- [ ] **Step 2: energy-gateway（reactive，无 datasource）**

- 删 `sanduo.jwt` 块中的 `secret: ${JWT_SECRET}` 行（保留 `expire-seconds`/`issuer`）。
- 做 Step 1 的编辑 B + C（gateway 的 `spring:` 块目前无 datasource/data/redis/kafka，直接加 `config.import`；nacos 块为 `spring.cloud.nacos.discovery`，补 username/password/config）。

- [ ] **Step 3: energy-tsdb（无 DataSource，删 TDengine 口令）**

- 删 `sanduo.tsdb` 块中的 `jdbc-password: ${TDENGINE_PASSWORD}` 行（保留 `jdbc-username: root`、`jdbc-url`）。
- 做 Step 1 的编辑 B + C。

- [ ] **Step 4: energy-mqtt-broker（特殊：nacos discovery 无 namespace，配置照补）**

- 删 `spring.datasource.password: ${MYSQL_PASSWORD}` 行。
- 编辑 B + C；broker 的 nacos 块原样为 `discovery.server-addr/group`，补 username/password 与 `config.server-addr`（namespace 可省，默认 public）。

- [ ] **Step 5: 验证——仓库零明文 + 结构齐备**

```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform"
echo "=== 明文口令残留（应为 0 命中）==="
grep -rn -E "${MYSQL_PASSWORD}|${TDENGINE_PASSWORD}|${JWT_SECRET}" backend --include="application.yml" || echo "零命中 OK"
echo "=== config.import 覆盖（应为 11 行）==="
grep -rln "nacos:energy-shared.yaml" backend --include="application.yml" | wc -l
echo "=== nacos username/password 覆盖（应各 11 行）==="
grep -rn "NACOS_USERNAME\|NACOS_PASSWORD" backend --include="application.yml" | wc -l
```
Expected: 明文 0 命中；`config.import` 11 行；username/password 共 22 行。

- [ ] **Step 6: 全后端构建**（确认 yml 语法与依赖不破坏编译）

```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/backend"
mvn -Dmaven.repo.local="/d/Program Files/maven-repo" package -DskipTests -q
```
Expected: BUILD SUCCESS（无 git 提交，构建即本任务交付验证）。

---

### Task 5: `start-stack.sh` 加载本地密钥 env

**Files:**
- Modify: `deploy/scripts/start-stack.sh`（`source lib.sh` 之后插入）

**Interfaces:**
- Consumes: `deploy/env/local.env`（Task 1）。
- Produces: 全栈启动时自动注入 `NACOS_USERNAME/NACOS_PASSWORD`（与 Task 4 的 `${NACOS_*}` 占位符闭合）。

- [ ] **Step 1: 插入加载块**

在 `source "${ROOT}/test/drill/lib.sh"`（第 11 行）之后插入：
```bash
# P0-4：本地密钥 env（gitignored）——存在才加载；缺失时依赖外部注入的 env（fail-fast 由服务启动兜底）
if [ -f "${ROOT}/deploy/env/local.env" ]; then
  source "${ROOT}/deploy/env/local.env"
  info "[Stack] 已加载 deploy/env/local.env（密钥 env）"
fi
```

- [ ] **Step 2: 语法与加载验证**

```bash
bash -n deploy/scripts/start-stack.sh && echo "语法 OK"
```
Expected: `语法 OK`。

---

### Task 6: 全栈启动验证 + fail-fast 负例

**Files:**（无代码改动，纯验证）

- [ ] **Step 1: 确认前置**——Nacos 8848 / Redis 6379 / MySQL 3306 就绪（`--skip-infra` 场景）。

- [ ] **Step 2: 全栈启动**

```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform"
./deploy/scripts/start-stack.sh --skip-infra --skip-build
```
Expected: 11 服务就绪；输出含「已加载 deploy/env/local.env」。

- [ ] **Step 3: 验证 Nacos 凭据生效（服务注册）**

登录 Nacos 取 accessToken 后查询实例列表：
```bash
TOKEN="$(curl -s -m 10 -X POST "http://127.0.0.1:8848/nacos/v1/auth/users/login" -d "username=nacos&password=${NACOS_PASSWORD}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
curl -s -m 10 "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=energy-system&groupName=ENERGY&accessToken=${TOKEN}"
```
Expected: `"hosts":[...]` 含 1 个实例 → 服务带凭据注册成功（匿名注册在服务端 console/admin 认证下仍应可见；重点是无 403 报错且集群健康）。

- [ ] **Step 4: 验证密钥经 Nacos 解析生效（DB 连通 → 网关路由）**

```bash
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/api/system/captcha
```
Expected: `200`。链路：网关→(lb)→energy-system；energy-system 启动时 Flyway 迁移成功 = MySQL 密码经 Nacos `spring.datasource.password` 解析成功；网关 JWT secret 经 Nacos 解析（登录接口验签可用）。

- [ ] **Step 5: 负例 fail-fast**

```bash
bash -c 'unset NACOS_USERNAME NACOS_PASSWORD; cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/backend"; java -jar energy-system/target/energy-system-1.0.0-SNAPSHOT.jar 2>&1 | head -20'
```
Expected: 启动中止，错误含 `Could not resolve placeholder 'NACOS_PASSWORD'`（证明无默认值占位符 fail-fast 生效）。注意：此命令会阻塞至报错，用 `timeout 40` 包裹或起后台观察。

- [ ] **Step 6: 失败兜底（仅当 Step 3/4 因 SCA config-import 可见性失败时）**

若服务报「无法解析 NACOS_PASSWORD」或 import 拉取失败但 Step 5 正常：在 `deploy/env/local.env` 追加直接环境变量形式（Spring 松绑定，config-data 加载阶段 env 即可用）：
```bash
export SPRING_CLOUD_NACOS_USERNAME='nacos'
export SPRING_CLOUD_NACOS_PASSWORD='${NACOS_PASSWORD}'
```
重新执行 Step 2~4。若仍失败，记录错误到部署日志并停栈回滚（还原 application.yml 前保留 git 无关快照：`cp -r backend deploy/backup-p0-4-yml`），回报告知。

---

### Task 7: 文档更新（Phase9 §5.5 + 记忆）

**Files:**
- Modify: `docs/design/Phase9-生产化差距分析.md`
- Create: 记忆文件（见 Step 3）

**Interfaces:**
- Consumes: Task 1~6 的落地事实与验证输出。

- [ ] **Step 1: Phase9 版本与状态行**

- 版本 `v1.3（+§5.4…）` → `v1.4（+§5.5 P0-4 密钥外置至 Nacos 配置中心）`。
- S-04 行（约 :304）`☐ 未开始` → `✅ 已修复`，证据列指向 §5.5。
- M-02 行（约 :319）`☐ 未开始` → `✅ 已修复`，证据指向 §5.5。
- §5 P0-4 roadmap 行（约 :132）标 `✅ 已落地：见 §5.5`。

- [ ] **Step 2: 新增 §5.5 落地记录**

内容要点（参考 §5.4 风格）：env keys 表（`NACOS_USERNAME/NACOS_PASSWORD`；`MYSQL_PASSWORD/JWT_SECRET/TDENGINE_PASSWORD` 仅 init 脚本消费）；dataId `energy-shared.yaml`（group ENERGY）持有三密钥字面值；`init-nacos-config.sh` 幂等推送；start-stack.sh 加载 local.env；**Nacos 认证边界**（服务端保持 console/admin 认证；客户端带凭据；prod 开启强制认证步骤：`nacos.core.auth.enabled=true` + 重启 Nacos 3.1.0）；SCA config-import 落地点；验证输出（dataId 回读、11 服务注册、captcha 200、fail-fast 负例）；附带说明（Nacos 成为服务硬启动依赖，与 discovery 一致）。

- [ ] **Step 3: 记忆更新**

- 更新 `MEMORY.md` 索引 + 新建/更新 `nacos-config-secrets.md`：dataId `energy-shared.yaml`（group ENERGY）、三密钥键名、`init-nacos-config.sh` 用法、`&` 值必须单引号、`config.server-addr` 必须显式（SCA 不回退 discovery.server-addr）、服务端 auth 边界。
- 若 Task 6 Step 6 走了 env 兜底，把「SCA config-import 可见性需 env 直出」写入该记忆。

- [ ] **Step 4: 收尾验证**

```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform"
echo "=== 最终明文扫描（排除本地 env）==="
grep -rn -E "${MYSQL_PASSWORD}|${TDENGINE_PASSWORD}|${JWT_SECRET}" backend deploy/scripts deploy/env/*.example docs || echo "零命中 OK"
```
Expected: 仅 `deploy/env/local.env`（gitignored）与 spec/plan 文档含明文（文档为设计记录，属允许），`backend` + `deploy/scripts` + `.example` 零命中。
