#!/usr/bin/env bash
# =====================================================================
# 演练 05：控制链路 P99（命令下发 → 设备 ACK → SUCCESS）
# 验证点：
#   1. 全链路可用：gateway(8000) → energy-command → iot-command-down
#      → energy-access → Broker PUBLISH down/command → 设备自动 ack
#      → iot-command-ack → energy-command 状态 SUCCESS；
#   2. 目标 P99 ≤ 500ms（架构指标），脚本自动判定 PASS/FAIL；
#   3. 该指标是后续压测/调优的回归基线。
# 前置：gateway、energy-command、energy-access、Broker、MySQL、Redis 全部运行，
#       设备已 seed（本脚本自动 seed 目标设备数）。
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/lib.sh"

log "========== 演练 05：控制链路 P99 =========="

# ---- 前置检查 ----
for svc in energy-gateway energy-command energy-access energy-mqtt-broker; do
  if [ -z "$(service_pid "$svc")" ]; then
    die "$svc 未运行"
  fi
done
if ! port_open 127.0.0.1 8000; then
  die "网关 8000 未监听"
fi
if ! port_open 127.0.0.1 6379; then
  die "Redis 未运行"
fi
if ! port_open 127.0.0.1 3306; then
  die "MySQL 未运行"
fi
if [ ! -f "$STRESS_JAR" ]; then
  die "缺少压测工具 $STRESS_JAR"
fi

COUNT=${COUNT:-200}        # 受控设备数（也是指令并发批次规模）
CONCURRENCY=${CONCURRENCY:-50}
TIMEOUT=${TIMEOUT:-10000}  # 单指令超时（ms）

# ---- 1. 造数（幂等）----
log "[Drill] 注册 $COUNT 台受控设备（INSERT IGNORE）..."
stress seed --count "$COUNT" --product snd_ess_pcs --secret-base sanduo-stress \
  >"${LOG_DIR}/05-seed.log" 2>&1 || true

# ---- 2. 网关探活（区分 404 业务错误与连接失败）----
log "[Drill] 网关探活 GET /api/command ..."
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://127.0.0.1:8000/api/command" || true)
if [ "$HTTP_CODE" = "000" ]; then
  die "网关不可达（HTTP 000），请确认 energy-gateway 已注册 Nacos"
fi
info "[Drill] 网关可达（HTTP $HTTP_CODE，404 属业务层正常响应）"
ok "网关/指令服务链路可达"

# ---- 3. 控制链路 P99 压测 ----
log "[Drill] 控制链路压测：$COUNT 设备 × $CONCURRENCY 并发，目标 P99 ≤ 500ms ..."
stress control --count "$COUNT" --concurrency "$CONCURRENCY" --timeout "$TIMEOUT" \
  --gateway http://127.0.0.1:8000 \
  >"${LOG_DIR}/05-control.log" 2>&1
rc=$?

cat "${LOG_DIR}/05-control.log"

if [ $rc -ne 0 ]; then
  err "控制链路压测未通过（exit=$rc），详见 ${LOG_DIR}/05-control.log"
  summary
  exit "$FAIL"
fi

# ---- 4. 读取 P99 判定 ----
P99=$(grep -oE 'P99=[0-9]+' "${LOG_DIR}/05-control.log" | head -1 | cut -d= -f2)
if [ -z "$P99" ]; then
  err "无法从压测结果解析 P99"
else
  if [ "$P99" -le 500 ]; then
    ok "控制链路 P99=${P99}ms ≤ 500ms（达成架构指标）"
  else
    err "控制链路 P99=${P99}ms > 500ms（超出指标，需排查指令链路）"
  fi
fi

summary
exit "$FAIL"
