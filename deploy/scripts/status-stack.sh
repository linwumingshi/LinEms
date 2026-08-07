#!/usr/bin/env bash
# =====================================================================
# EnergyX 平台全栈状态检查
# 输出：服务/端口/进程/基础环境一览 + Broker 统计
# =====================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT}/test/drill/lib.sh"

log "========== EnergyX 平台全栈状态 =========="

echo "--- 后端服务（就绪端口 / 进程） ---"
printf '%-22s %-8s %-10s %s\n' "服务" "端口" "进程" "状态"
for entry in \
    "energy-gateway:8000" "energy-system:8101" "energy-product:8102" \
    "energy-device:8103" "energy-station:8104" "energy-mqtt-broker:1883" \
    "energy-access:8111" "energy-tsdb:8112" "energy-shadow:8113" \
    "energy-command:8114" "energy-alarm:8115" "energy-ems:8105"; do
  name="${entry%%:*}"; port="${entry##*:}"
  pid="$(service_pid "$name")"
  if port_open 127.0.0.1 "$port" && [ -n "$pid" ]; then
    printf '%-22s %-8s %-10s %s\n' "$name" "$port" "${pid:-}" "UP ✅"
  elif [ -n "$pid" ]; then
    printf '%-22s %-8s %-10s %s\n' "$name" "$port" "$pid" "进程在，端口未开 ⚠️"
  else
    printf '%-22s %-8s %-10s %s\n' "$name" "$port" "-" "DOWN ❌"
  fi
done

echo "--- 基础环境 ---"
for item in "nacos:8848" "nacos-grpc:9848" "kafka:9092" "redis:6379" "elasticsearch:9200" "tdengine:6030" "mysql:3306"; do
  name="${item%%:*}"; port="${item##*:}"
  if port_open 127.0.0.1 "$port"; then
    printf '%-20s %-8s %s\n' "$name" "$port" "UP ✅"
  else
    printf '%-20s %-8s %s\n' "$name" "$port" "DOWN ❌"
  fi
done

echo "--- Broker 统计（8082） ---"
json="$(broker_stats_json)"
if [ "$json" = "{}" ]; then
  echo "  Broker 统计不可达（进程未起或 8082 未监听）"
else
  for f in nodeId connections subscriptions messagesIn messagesOut acceptedConnections rejectedConnections authFailures uptimeMillis; do
    printf '  %-22s = %s\n' "$f" "$(echo "$json" | sed -n "s/.*\"$f\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1)"
  done
fi

echo "--- Nacos 服务实例（可选：需 nacos 起） ---"
if port_open 127.0.0.1 8848; then
  curl -sf -m 5 "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=50&groupName=ENERGY" \
    | sed -n 's/.*"doms":\[\(.*\)\].*/  \1/p' | tr ',' '\n' | sed 's/^/  /' || echo "  Nacos 查询失败"
else
  echo "  Nacos 未运行"
fi
exit 0
