#!/usr/bin/env bash
# =====================================================================
# 三多平台全栈启动脚本（Git Bash / WSL 下运行）
# 顺序：基础环境(Nacos/Kafka/Redis/ES/TDengine) → 后端服务 → 网关 → 就绪轮询
# 用法：./start-stack.sh [--skip-build] [--skip-infra]
#   --skip-build  跳过后端 jar 缺失检查与构建（已构建过时加速）
#   --skip-infra  跳过 docker compose（基础环境已运行）
# =====================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT}/test/drill/lib.sh"
# P0-4：本地密钥 env（gitignored）——存在才加载；缺失时依赖外部注入的 env（fail-fast 由服务启动兜底）
if [ -f "${ROOT}/deploy/env/local.env" ]; then
  source "${ROOT}/deploy/env/local.env"
  info "[Stack] 已加载 deploy/env/local.env（密钥 env）"
fi
LOG_DIR="${ROOT}/deploy/logs"
mkdir -p "$LOG_DIR"

SKIP_BUILD=false
SKIP_INFRA=false
for a in "$@"; do
  case "$a" in
    --skip-build) SKIP_BUILD=true ;;
    --skip-infra) SKIP_INFRA=true ;;
    *) info "[Stack] 忽略未知参数: $a" ;;
  esac
done

MAVEN_REPO="/d/Program Files/maven-repo"

# 服务 → 就绪端口（MQTT Broker 用设备接入端口 1883，其余用各自 server.port）
SERVICES=(
  "energy-system:8101"
  "energy-product:8102"
  "energy-device:8103"
  "energy-station:8104"
  "energy-mqtt-broker:1883"
  "energy-access:8111"
  "energy-tsdb:8112"
  "energy-shadow:8113"
  "energy-command:8114"
  "energy-alarm:8115"
  "energy-ems:8105"
)
GATEWAY_PORT=8000

# 基础环境就绪端口
INFRA_PORTS="8848 9848 9092 6379 9200 6030"

log "========== 三多平台全栈启动 =========="
info "[Stack] 日志目录: $LOG_DIR"

# ----------------------------------------------------------------
# 0. jar 缺失检查与构建
# ----------------------------------------------------------------
if [ "$SKIP_BUILD" = false ]; then
  MISSING=""
  for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    if ! ls "${ROOT}/backend/${name}/target/"*.jar >/dev/null 2>&1; then
      MISSING="$MISSING $name"
    fi
  done
  if ! ls "${ROOT}/backend/energy-gateway/target/"*.jar >/dev/null 2>&1; then
    MISSING="$MISSING energy-gateway"
  fi
  if [ -n "$MISSING" ]; then
    info "[Stack] 缺少 jar:$MISSING，开始后端全量构建（首次需数分钟）..."
    ( cd "${ROOT}/backend" && mvn -Dmaven.repo.local="$MAVEN_REPO" package -DskipTests ) \
      >"$LOG_DIR/backend-build.log" 2>&1 || {
      err "后端构建失败，详见 $LOG_DIR/backend-build.log"
      exit 1
    }
    ok "后端构建完成"
  else
    info "[Stack] 后端 jar 齐全，跳过构建（--skip-build 可强制跳过检查）"
  fi
fi

# ----------------------------------------------------------------
# 1. 基础环境（Docker Compose）
# ----------------------------------------------------------------
if [ "$SKIP_INFRA" = false ]; then
  log "[Stack] 启动基础环境（Nacos/Kafka/Redis/ES/TDengine）..."
  if ! docker_ready; then
    err "Docker daemon 不可用（请先启动 Docker Desktop）"
    exit 1
  fi
  compose up -d >"$LOG_DIR/compose-up.log" 2>&1 || {
    err "docker compose up 失败，详见 $LOG_DIR/compose-up.log"
    exit 1
  }
  for p in $INFRA_PORTS; do
    if wait_port_up 127.0.0.1 "$p" 180; then
      ok "基础环境端口 $p 就绪"
    else
      err "基础环境端口 $p 等待超时（Nacos 需先于服务启动）"
    fi
  done
else
  info "[Stack] --skip-infra：跳过 docker compose，按已运行的基础环境处理"
fi

if ! port_open 127.0.0.1 3306; then
  err "MySQL 3306 未监听（请启动本机 MySQL 服务或 docker compose 的 mysql 服务）"
  exit 1
fi
ok "MySQL 3306 就绪"

# ----------------------------------------------------------------
# 2. 启动后端服务（业务服务先、Broker 先于接入、网关最后）
# ----------------------------------------------------------------
start_one() { # 模块名 就绪端口
  local name=$1 port=$2 pid jar
  pid="$(service_pid "$name")"
  if [ -n "$pid" ]; then
    info "[Stack] $name 已在运行（pid $pid），跳过"
    return 0
  fi
  jar="$(ls "${ROOT}/backend/${name}/target/"*.jar 2>/dev/null | head -1)"
  if [ -z "$jar" ]; then
    err "[Stack] 缺少 ${name}/target/*.jar"
    return 1
  fi
  nohup java -jar "$jar" >"$LOG_DIR/${name}.log" 2>&1 &
  echo $! >"$LOG_DIR/${name}.pid"
  log "[Stack] 启动 $name (pid $!, 就绪端口 $port)"
}

for entry in "${SERVICES[@]}"; do
  start_one "${entry%%:*}" "${entry##*:}"
done
start_one "energy-gateway" "$GATEWAY_PORT"

# ----------------------------------------------------------------
# 3. 并行等待所有服务就绪
# ----------------------------------------------------------------
DEADLINE=150
log "[Stack] 等待服务就绪（最长 ${DEADLINE}s）..."
PENDING=()
for entry in "${SERVICES[@]}"; do
  PENDING+=("${entry%%:*}:${entry##*:}")
done
PENDING+=("energy-gateway:${GATEWAY_PORT}")

i=0
while (( i < DEADLINE )); do
  STILL=()
  for item in "${PENDING[@]}"; do
    name="${item%%:*}"; port="${item##*:}"
    if port_open 127.0.0.1 "$port"; then
      ok "$name 就绪（端口 $port）"
    else
      STILL+=("$item")
    fi
  done
  PENDING=("${STILL[@]}")
  [ ${#PENDING[@]} -eq 0 ] && break
  sleep 5
  i=$((i + 5))
done

if [ ${#PENDING[@]} -gt 0 ]; then
  err "以下服务未在 ${DEADLINE}s 内就绪："
  for item in "${PENDING[@]}"; do
    name="${item%%:*}"
    err "  $name 未就绪（日志: $LOG_DIR/$name.log 尾部）"
    tail -5 "$LOG_DIR/$name.log" 2>/dev/null | sed 's/^/    /'
  done
  info "[Stack] 查看完整日志: $LOG_DIR/<模块>.log"
  exit 1
fi

# ----------------------------------------------------------------
# 4. 汇总
# ----------------------------------------------------------------
log "========== 全栈启动完成 =========="
echo "  网关:          http://127.0.0.1:8000"
echo "  Broker MQTT:   tcp://127.0.0.1:1883  统计: http://127.0.0.1:8082/internal/broker/stats"
echo "  Nacos 控制台:  http://127.0.0.1:8848/nacos"
echo "  服务注册:      登录 Nacos 查看 energy-* 全部实例"
echo "  压测/演练:     cd test/drill && ./run-all.sh"
exit 0
