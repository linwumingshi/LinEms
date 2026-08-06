#!/usr/bin/env bash
# =====================================================================
# 演练 03：Redis 故障降级（fail-closed）
# 验证点：
#   1. 基线：Broker 保持 N 条真实连接；
#   2. 停止 Redis：新连接被拒绝（认证走 nonce SETNX，Redis 不可用即
#      deny(3) 认证服务异常——fail-closed），但 Broker 进程/端口存活；
#   3. 既有内存会话不受影响（统计连接数不归零）；
#   4. 恢复 Redis：新连接立即恢复成功。
# 前置：Broker 运行、Redis 容器（ems-redis）、设备已 seed。
# 注：演练中断（Ctrl+C）时由 trap 兜底恢复 Redis。
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/lib.sh"

log "========== 演练 03：Redis 故障降级（fail-closed）=========="

# ---- 前置检查 ----
if [ -z "$(service_pid energy-mqtt-broker)" ]; then
  die "Broker 未运行（energy-mqtt-broker）"
fi
if ! docker_ready; then
  die "Docker daemon 不可用（演练通过 ems-redis 容器停启故障，请先启动 Docker）"
fi
if ! port_open 127.0.0.1 6379; then
  die "Redis 未运行"
fi
if [ ! -f "$STRESS_JAR" ]; then
  die "缺少压测工具 $STRESS_JAR"
fi

BASE_COUNT=${BASE_COUNT:-50}

# 中断/失败/正常收尾均兜底恢复 Redis（compose start 幂等）
trap 'compose start redis >/dev/null 2>&1 || true' EXIT INT TERM

# ---- 1. 基线保持连接（后台，覆盖整个演练窗口）----
log "[Drill] 建立 $BASE_COUNT 条保持连接 ..."
stress connect --count "$BASE_COUNT" --concurrency 25 --subscribe false \
  --hold-seconds 300 --io-threads 16 >"${LOG_DIR}/03-hold.log" 2>&1 &
if ! wait_connections "$BASE_COUNT" 90; then
  kill_stress
  die "基线连接未达 $BASE_COUNT，当前 $(broker_connections)"
fi
ok "基线：Broker 连接数 $(broker_connections) ≥ $BASE_COUNT"

# ---- 2. 停止 Redis ----
log "[Drill] 停止 Redis 容器 ..."
compose stop redis >/dev/null 2>&1 || docker stop ems-redis >/dev/null 2>&1
if ! wait_port_down 127.0.0.1 6379 30; then
  die "Redis 端口 6379 未关闭"
fi
info "[Drill] Redis 已停止（6379 关闭），新连接认证将走 fail-closed"

# ---- 3. 新连接应被拒绝 ----
sleep 2
stress connect --count 10 --concurrency 10 --subscribe false --io-threads 4 \
  >"${LOG_DIR}/03-during.log" 2>&1
rc=$?
if [ $rc -eq 0 ]; then
  err "Redis 宕机期间新连接不应成功（fail-closed 失效，认证未走 Redis）"
else
  ok "Redis 宕机期间新连接全部被拒绝（fail-closed，exit=$rc）"
fi

# ---- 4. 既有会话存续 + Broker 存活 ----
sleep 3
conn_now=$(broker_connections)
if [ -z "$conn_now" ] || [ "$conn_now" -eq 0 ]; then
  err "既有连接全部掉线（预期存续）"
else
  ok "既有会话存活：Broker 统计连接数 $conn_now"
fi
if [ -z "$(service_pid energy-mqtt-broker)" ] || ! port_open 127.0.0.1 $BROKER_MQTT_PORT; then
  err "Broker 不应因 Redis 宕机而挂掉"
else
  ok "Broker 进程与 $BROKER_MQTT_PORT 端口正常（故障隔离）"
fi

# ---- 5. 恢复 Redis ----
log "[Drill] 恢复 Redis ..."
compose start redis >/dev/null 2>&1 || docker start ems-redis >/dev/null 2>&1
if ! wait_port_up 127.0.0.1 6379 60; then
  die "Redis 未恢复（6379 未打开）"
fi
info "[Drill] Redis 已恢复"

# ---- 6. 新连接恢复成功 ----
sleep 2
stress connect --count 10 --concurrency 10 --subscribe false --io-threads 4 \
  >"${LOG_DIR}/03-after.log" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  err "Redis 恢复后新连接仍失败（exit=$rc）"
else
  ok "Redis 恢复后新连接 10/10 成功"
fi

# ---- 清理 ----
trap - EXIT INT TERM
kill_stress
summary
exit "$FAIL"
