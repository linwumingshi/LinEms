#!/usr/bin/env bash
# =====================================================================
# 演练 02：Broker 重启与连接自动恢复
# 验证点：
#   1. 压测工具保持 N 条真实设备连接；
#   2. kill Broker → 端口关闭 → 既有连接全部断开；
#   3. 重启 Broker（服务可独立拉起）→ 设备 SDK 指数退避自动重连，
#      Broker 统计连接数逐步恢复至目标值（≤120s）；
#   4. 全程无人工干预，验证自愈能力。
# 前置：Broker 运行、Redis up、设备已 seed。
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/lib.sh"

log "========== 演练 02：Broker 重启与连接自动恢复 =========="

# ---- 前置检查 ----
if [ -z "$(service_pid energy-mqtt-broker)" ]; then
  die "Broker 未运行（energy-mqtt-broker）"
fi
if ! port_open 127.0.0.1 6379; then
  die "Redis 未运行"
fi
if [ ! -f "$STRESS_JAR" ]; then
  die "缺少压测工具 $STRESS_JAR"
fi

HOLD_COUNT=${HOLD_COUNT:-100}       # 保持连接数
HOLD_SECONDS=${HOLD_SECONDS:-600}   # 后台保持时长（覆盖整个演练）

# ---- 1. 建立保持连接（后台）----
log "[Drill] 建立 $HOLD_COUNT 条保持连接（后台，最长 $HOLD_SECONDS 秒）..."
stress connect --count "$HOLD_COUNT" --concurrency 50 --subscribe false \
  --hold-seconds "$HOLD_SECONDS" --io-threads 16 \
  >"${LOG_DIR}/02-hold.log" 2>&1 &
CONN_PID=$!

if ! wait_connections "$HOLD_COUNT" 90; then
  kill_stress
  die "基线连接未达 $HOLD_COUNT，当前 $(broker_connections)。日志: ${LOG_DIR}/02-hold.log"
fi
ok "基线：Broker 统计连接数 $(broker_connections) ≥ $HOLD_COUNT"

# ---- 2. 停止 Broker ----
log "[Drill] 停止 Broker（kill 进程，模拟节点崩溃）..."
stop_service energy-mqtt-broker
if ! wait_port_down 127.0.0.1 $BROKER_MQTT_PORT 30; then
  kill_stress
  die "Broker 端口 $BROKER_MQTT_PORT 未按预期关闭"
fi
info "[Drill] Broker 已停止：$BROKER_MQTT_PORT 端口关闭，既有连接全部断开"
sleep 2
info "[Drill] 当前统计接口已不可达（Broker 进程已退出），符合预期"

# ---- 3. 重启 Broker ----
start_service energy-mqtt-broker
if ! wait_port_up 127.0.0.1 $BROKER_MQTT_PORT 90; then
  kill_stress
  die "Broker 重启失败：$BROKER_MQTT_PORT 未恢复。日志: ${LOG_DIR}/energy-mqtt-broker.log"
fi
info "[Drill] Broker 已重启，等待设备 SDK 指数退避自动重连（基数 1s，上限 30s）..."

# ---- 4. 等待连接自动恢复 ----
if ! wait_connections "$HOLD_COUNT" 120; then
  kill_stress
  die "自动重连恢复超时：当前 $(broker_connections)/$HOLD_COUNT，预期全部恢复"
fi
ok "SDK 自动重连成功：Broker 连接数恢复至 $(broker_connections)/$HOLD_COUNT（无人工干预）"

# ---- 5. 验证恢复后的连接仍可正常认证上行 ----
log "[Drill] 恢复后再开 50 条新连接，验证认证/接入能力完好 ..."
stress connect --count 50 --concurrency 25 --subscribe false --io-threads 16 \
  >"${LOG_DIR}/02-after.log" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  err "恢复后新连接失败（exit=$rc），认证/接入未完全恢复"
else
  ok "恢复后新连接 50/50 成功，接入能力完好"
fi

# ---- 清理 ----
kill_stress
sleep 1
summary
exit "$FAIL"
