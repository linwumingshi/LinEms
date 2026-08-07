#!/usr/bin/env bash
# =====================================================================
# EnergyX 平台故障演练公共函数库（Git Bash / WSL 下运行）
# =====================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# P0-4：本地密钥 env（gitignored）——存在才加载（stress seed 的 MYSQL_PASSWORD 等由此注入）
if [ -f "${ROOT}/deploy/env/local.env" ]; then
  source "${ROOT}/deploy/env/local.env"
fi
DRILL_DIR="${ROOT}/test/drill"
LOG_DIR="${DRILL_DIR}/logs"
STRESS_JAR="${ROOT}/test/stress/target/stress.jar"
COMPOSE_FILE="${ROOT}/deploy/docker/docker-compose.yml"
BROKER_HTTP_PORT=8082
BROKER_MQTT_PORT=1883

mkdir -p "${LOG_DIR}"
PASS=0
FAIL=0

log()  { printf '\033[36m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
info() { printf '\033[33m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
ok()   { PASS=$((PASS+1)); printf '\033[32m[PASS] %s\033[0m\n' "$*"; }
err()  { FAIL=$((FAIL+1)); printf '\033[31m[FAIL] %s\033[0m\n' "$*"; }
die()  {
  printf '\033[31m[FAIL] %s\033[0m\n' "$*"
  printf '\n==================== 演练结果 ====================\n'
  printf '  结论: 演练中止（前置条件不满足） ❌\n'
  printf '==================================================\n'
  exit 1
}
summary() {
  printf '\n==================== 演练结果 ====================\n'
  printf '  PASS: %d   FAIL: %d\n' "$PASS" "$FAIL"
  if [ "$FAIL" -eq 0 ]; then
    printf '  结论: 演练通过 ✅\n'
  else
    printf '  结论: 演练未通过 ❌\n'
  fi
  printf '==================================================\n'
}

# ----------------------------------------------------------------
# 探测 / 等待
# ----------------------------------------------------------------
port_open() { # host port
  (exec 3<>/dev/tcp/"$1"/"$2") 2>/dev/null && { exec 3>&-; exec 3<&-; return 0; }
  return 1
}

wait_port_down() { # host port timeout_sec
  local host=$1 port=$2 timeout=${3:-15} i=0
  while (( i < timeout )); do
    if ! port_open "$host" "$port"; then return 0; fi
    sleep 1; i=$((i+1))
  done
  return 1
}

wait_port_up() { # host port timeout_sec
  local host=$1 port=$2 timeout=${3:-30} i=0
  while (( i < timeout )); do
    if port_open "$host" "$port"; then return 0; fi
    sleep 1; i=$((i+1))
  done
  return 1
}

# ----------------------------------------------------------------
# Broker 统计（HTTP 8082 /internal/broker/stats）
# ----------------------------------------------------------------
broker_stats_json() {
  curl -sf -m 3 "http://127.0.0.1:${BROKER_HTTP_PORT}/internal/broker/stats" 2>/dev/null || echo "{}"
}

broker_field() { # field  (connections / messagesIn / authFailures / ...)
  local json
  json="$(broker_stats_json)"
  echo "$json" | sed -n "s/.*\"$1\":\([0-9][0-9]*\).*/\1/p" | head -1
}

broker_connections() { broker_field connections; }

# ----------------------------------------------------------------
# 进程管理（服务以 java -jar 运行，通过 jps 定位）
# ----------------------------------------------------------------
service_pid() { # 关键字（energy-mqtt-broker / energy-access / energy-gateway ...）
  jps -l 2>/dev/null | grep -i "$1" | awk '{print $1}' | head -1
}

stop_service() { # 关键字
  local pid
  pid="$(service_pid "$1")"
  if [ -z "$pid" ]; then
    info "[Drill] $1 未在运行，跳过停止"
    return 0
  fi
  log "[Drill] 停止 $1 (pid $pid)"
  kill "$pid" 2>/dev/null || true
  local i=0
  while (( i < 20 )); do
    if ! ps -p "$pid" >/dev/null 2>&1; then return 0; fi
    sleep 1; i=$((i+1))
  done
  kill -9 "$pid" 2>/dev/null || true
}

start_service() { # 模块目录（energy-mqtt-broker ...），额外参数走 SERVICE_ARGS 环境变量
  local module=$1 jar
  jar="$(ls "${ROOT}/backend/${module}/target/"*.jar 2>/dev/null | head -1)"
  if [ -z "$jar" ]; then
    die "[Drill] 缺少 ${module}/target/*.jar，请先 cd backend && mvn package"
  fi
  log "[Drill] 启动 ${module}: $(basename "$jar")"
  nohup java -jar "$jar" ${SERVICE_ARGS:-} >"${LOG_DIR}/${module}.log" 2>&1 &
  echo $! >"${LOG_DIR}/${module}.pid"
}

# ----------------------------------------------------------------
# 压测工具
# ----------------------------------------------------------------
stress() { # 转发全部参数到 stress.jar
  if [ ! -f "$STRESS_JAR" ]; then
    die "[Drill] 缺少压测工具 ${STRESS_JAR}，请先 cd test/stress && mvn package"
  fi
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "$STRESS_JAR" "$@"
}

# ----------------------------------------------------------------
# Docker 编排（Kafka / Redis）
# ----------------------------------------------------------------
compose() { # 转发参数到 docker compose -f
  docker compose -f "$COMPOSE_FILE" "$@"
}

docker_ready() { # Docker daemon 可用性（带超时，避免 daemon 卡死挂起脚本）
  timeout 15 docker info >/dev/null 2>&1
}

# ----------------------------------------------------------------
# Kafka 工具（经 docker compose exec 进入 bitnami/kafka 容器）
# ----------------------------------------------------------------
kafka_cmd() { # kafka-脚本名 args...
  compose exec -T kafka "//opt/kafka/bin/$1" --bootstrap-server 127.0.0.1:9092 "${@:2}"
}

kafka_group_describe() { # group
  kafka_cmd kafka-consumer-groups.sh --describe --group "$1" 2>/dev/null
}

kafka_group_lag_sum() { # group → 各分区 LAG 求和（无消费者时为全量积压）
  local header col
  header="$(kafka_group_describe "$1" | head -1)"
  col="$(echo "$header" | tr ' ' '\n' | grep -nx 'LAG' | head -1 | cut -d: -f1)"
  if [ -z "$col" ]; then
    echo 0
    return
  fi
  kafka_group_describe "$1" | tail -n +2 | awk -v c="$col" 'NF>=c {s+=$c} END {print s+0}'
}

# ----------------------------------------------------------------
# Broker 连接数等待 / 压测进程清理
# ----------------------------------------------------------------
wait_connections() { # target timeout_sec
  local target=$1 timeout=${2:-60} i=0 now
  while (( i < timeout )); do
    now="$(broker_connections)"
    if [ "${now:-0}" -ge "$target" ]; then
      return 0
    fi
    sleep 2
    i=$((i + 2))
  done
  return 1
}

kill_stress() { # 杀掉全部 java -jar stress.jar 压测进程
  pkill -f "stress.jar" 2>/dev/null || true
}
