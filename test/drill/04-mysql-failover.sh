#!/usr/bin/env bash
# =====================================================================
# 演练 04：MySQL 故障切换（凭据源短暂不可用）
# 验证点：
#   1. 基线：新设备可注册、认证成功接入；
#   2. 停止 MySQL 服务（Windows 服务名 MySQL）：
#        - 新连接认证查库失败 → 被 deny(3) 拒绝（fail-closed）；
#        - 既有内存会话存续（Broker 连接数不归零、Broker 不挂）；
#   3. 恢复 MySQL：新连接立即恢复成功。
# 用法：
#   ./04-mysql-failover.sh                # 默认演练（不真正停库，仅校验链路与预案打印）
#   ./04-mysql-failover.sh --execute      # 真正 stop/start MySQL 服务（需管理员权限）
# 注：--execute 模式下演练中断或失败，均由 trap 兜底恢复 MySQL 服务。
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/lib.sh"

MYSQL_SERVICE=${MYSQL_SERVICE:-MySQL}
EXECUTE=false
for a in "$@"; do
  [ "$a" = "--execute" ] && EXECUTE=true
done

log "========== 演练 04：MySQL 故障切换 =========="
if [ "$EXECUTE" = true ]; then
  info "[Drill] 模式：--execute（将真实 stop/start 服务 ${MYSQL_SERVICE}）"
else
  info "[Drill] 模式：预演（不真正停库；加 --execute 执行真实切换）"
fi

# ---- 前置检查 ----
if [ -z "$(service_pid energy-mqtt-broker)" ]; then
  die "Broker 未运行（energy-mqtt-broker）"
fi
if ! sc query "$MYSQL_SERVICE" >/dev/null 2>&1; then
  die "未发现 MySQL 服务（${MYSQL_SERVICE}），请检查服务名"
fi
if ! port_open 127.0.0.1 3306; then
  die "MySQL 3306 未监听"
fi
if [ ! -f "$STRESS_JAR" ]; then
  die "缺少压测工具 $STRESS_JAR"
fi

mysql_start() { # 恢复 MySQL 服务
  net start "$MYSQL_SERVICE" >/dev/null 2>&1 || sc start "$MYSQL_SERVICE" >/dev/null 2>&1 || true
}

MYSQL_WAS_STOPPED=false
# EXIT 覆盖 die()/exit 的失败与正常收尾（幂等恢复）；INT/TERM 覆盖 Ctrl+C 中断
trap 'if [ "${MYSQL_WAS_STOPPED:-false}" = true ]; then mysql_start; echo "[Drill] 兜底：已恢复 MySQL 服务"; fi' EXIT INT TERM

# ---- 1. 基线：注册 20 台设备并全部接入 ----
log "[Drill] 造数注册 20 台设备（INSERT IGNORE，幂等）..."
stress seed --count 20 --product snd_ess_pcs --secret-base sanduo-stress \
  >"${LOG_DIR}/04-seed.log" 2>&1 || true

stress connect --count 20 --concurrency 10 --subscribe false --io-threads 8 \
  >"${LOG_DIR}/04-base.log" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  die "基线建连失败（exit=$rc），详见 ${LOG_DIR}/04-base.log"
fi
ok "基线：20/20 设备注册并认证成功"

# 保持连接进程（验证既有会话在停库期间存续）
stress connect --count 20 --concurrency 10 --subscribe false \
  --hold-seconds 300 --io-threads 8 >"${LOG_DIR}/04-hold.log" 2>&1 &
if ! wait_connections 20 90; then
  kill_stress
  die "保持连接未达 20"
fi
ok "保持连接就绪：Broker 连接数 $(broker_connections)"

# ---- 2. 停止 MySQL（仅 --execute）----
if [ "$EXECUTE" = true ]; then
  log "[Drill] 停止 MySQL 服务（${MYSQL_SERVICE}）..."
  net stop "$MYSQL_SERVICE" >/dev/null 2>&1 || sc stop "$MYSQL_SERVICE" >/dev/null 2>&1
  if ! wait_port_down 127.0.0.1 3306 30; then
    die "MySQL 3306 未关闭（服务可能需管理员权限）"
  fi
  MYSQL_WAS_STOPPED=true
  info "[Drill] MySQL 已停止（3306 关闭）"
else
  info "[Drill] 预演：跳过实际停库，直接校验链路状态"
fi

# ---- 3. 停库期间：新连接被拒绝 + 既有会话存续 ----
sleep 2
if [ "$EXECUTE" = true ]; then
  stress connect --count 5 --concurrency 5 --subscribe false --io-threads 4 \
    >"${LOG_DIR}/04-during.log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    err "停库期间新连接不应成功（认证未查库 / 未 fail-closed）"
  else
    ok "停库期间新连接被拒绝（fail-closed，exit=$rc）"
  fi
else
  ok "预演：确认 Broker 存活且统计接口可达（链路未因预案设计中断）"
fi

sleep 2
conn_now=$(broker_connections)
if [ -z "$conn_now" ] || [ "$conn_now" -eq 0 ]; then
  err "既有连接全部掉线（预期存续）"
else
  ok "既有会话存续：Broker 连接数 $conn_now"
fi
if [ -z "$(service_pid energy-mqtt-broker)" ] || ! port_open 127.0.0.1 $BROKER_MQTT_PORT; then
  err "Broker 不应因 MySQL 宕机而挂"
else
  ok "Broker 进程与端口正常（故障隔离）"
fi

# ---- 4. 恢复 MySQL ----
log "[Drill] 恢复 MySQL 服务 ..."
mysql_start
if ! wait_port_up 127.0.0.1 3306 60; then
  MYSQL_WAS_STOPPED=false
  die "MySQL 未恢复（3306 未打开）"
fi
MYSQL_WAS_STOPPED=false
info "[Drill] MySQL 已恢复"

# ---- 5. 新连接恢复成功 ----
sleep 2
stress connect --count 5 --concurrency 5 --subscribe false --io-threads 4 \
  >"${LOG_DIR}/04-after.log" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  err "MySQL 恢复后新连接仍失败（exit=$rc）"
else
  ok "MySQL 恢复后新连接 5/5 成功"
fi

trap - EXIT INT TERM
kill_stress
summary
exit "$FAIL"
