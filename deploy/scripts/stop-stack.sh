#!/usr/bin/env bash
# =====================================================================
# 三多平台全栈停止脚本
# 用法：./stop-stack.sh [--infra]
#   --infra  连带停止 Docker 基础环境（Nacos/Kafka/Redis/ES/TDengine）
# =====================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT}/test/drill/lib.sh"

STOP_INFRA=false
[ "${1:-}" = "--infra" ] && STOP_INFRA=true

log "========== 三多平台全栈停止 =========="

# 停止后端服务（网关最后启动、最先停；顺序无强依赖，逐个停）
for name in energy-gateway energy-alarm energy-ems energy-command energy-shadow \
            energy-tsdb energy-access energy-mqtt-broker \
            energy-station energy-device energy-product energy-system; do
  stop_service "$name"
done

if [ "$STOP_INFRA" = true ]; then
  log "[Stack] 停止 Docker 基础环境..."
  compose down >/dev/null 2>&1 || docker compose -f "$COMPOSE_FILE" down >/dev/null 2>&1 || true
  ok "基础环境已停止（compose down）"
fi

ok "全部后端服务已停止"
exit 0
