#!/usr/bin/env bash
# =====================================================================
# P0-5 首轮真实压测 + P99 基线编排
# 依赖：test/stress/target/stress.jar（已构建）；test/drill/lib.sh
# 前置：全栈已启动（Nacos/Kafka/Redis/MySQL/TDengine + 11 服务）；
#       MYSQL_PASSWORD 由 lib.sh 加载 deploy/env/local.env 注入
# 用法：bash test/stress/run-baseline.sh [--max-tiers 4] [--duration 60]
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/../drill/lib.sh"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="${ROOT}/test/stress/results/${RUN_ID}"
mkdir -p "$RESULTS_DIR"

MAX_TIERS="${MAX_TIERS:-4}"     # 最多压到第几档（1-4）
DURATION="${DURATION:-60}"      # 每档时长（秒）
TD_HTTP="http://127.0.0.1:6041/rest/sql"
TD_USER="root"; TD_PASS="taosdata"

log "========== P0-5 基线压测 run-baseline 开始 =========="
log "运行目录: ${RESULTS_DIR}"

# ---------------------------------------------------------------
# 工具函数：TDengine REST（raw SQL body）
# ---------------------------------------------------------------
td_sql() { # sql → JSON 输出
  curl -s -m 15 -u "${TD_USER}:${TD_PASS}" -d "$1" "$TD_HTTP"
}

td_count() { # 库.表 → 行数（解析 count(*) 结果 data[0][0]）
  td_sql "SELECT count(*) FROM $1" | sed -n 's/.*"data":\[\[\([0-9][0-9]*\)\]\].*/\1/p'
}

# ---------------------------------------------------------------
# 0. 前置检查（fail-fast）
# ---------------------------------------------------------------
log "[1/6] 前置检查 ..."
for item in "nacos:8848" "kafka:9092" "broker-mgmt:8082" "tdengine:6041" "mysql:3306"; do
  host=${item%:*}; port=${item##*:}
  if ! port_open 127.0.0.1 "$port"; then
    die "${host}:${port} 未监听（${item}）——请先启动全栈"
  fi
done
if [ ! -f "$STRESS_JAR" ]; then
  die "缺少压测工具 ${STRESS_JAR}（先 cd test/stress && mvn package）"
fi
info "[1/6] 前置检查通过"

# ---------------------------------------------------------------
# 1. TDengine 初始化（幂等；G-2 单节点变体 DDL）
# ---------------------------------------------------------------
log "[2/6] TDengine 初始化（G-2：DURATION/WAL_FSYNC_PERIOD/REPLICA 1/VGROUPS 8）..."
if ! td_sql "SHOW DATABASES" | grep -q iot_tsdb_raw; then
  td_sql "CREATE DATABASE IF NOT EXISTS iot_tsdb_raw PRECISION 'ms' KEEP 365 DURATION 10 BUFFER 256 WAL_LEVEL 2 WAL_FSYNC_PERIOD 0 REPLICA 1 VGROUPS 8"
  # TDengine 建库有 dropping 状态窗口，重试至成功
  for i in 1 2 3 4 5; do
    if td_sql "SHOW DATABASES" | grep -q iot_tsdb_raw; then break; fi
    info "  等待建库生效（${i}）..."; sleep 2
  done
fi
td_code() { # sql → TDengine code（0=成功，非0=错误；curl 失败返回 999）
  local out
  out="$(td_sql "$1")" || { echo 999; return; }
  echo "$out" | sed -n 's/.*"code":\([-0-9]*\).*/\1/p'
}

# st_prop_snd_ess_pcs：压测主路径 load-bearing，建表失败须 fail-fast
if [ "$(td_code "CREATE STABLE IF NOT EXISTS iot_tsdb_raw.st_prop_snd_ess_pcs (ts TIMESTAMP, msg_id NCHAR(64), data_type NCHAR(16), soc FLOAT, voltage FLOAT, current FLOAT, power FLOAT, temp FLOAT, run_mode INT) TAGS (device_id NCHAR(64), station_id NCHAR(32), enterprise_id NCHAR(32), product_key NCHAR(64))")" != 0 ]; then
  die "st_prop_snd_ess_pcs 建表失败（压测主路径不可用，请检查 TDengine 与修正 DDL）"
fi
# st_event：非压测主路径（ThroughputLoad 不产事件）。payload JSON 普通列被 TDengine 拒绝
# （JSON 仅可作 TAG，错误 9810）——G-2 差距证据，如实告警但不中止（报告 §G-2 收录）
if [ "$(td_code "CREATE STABLE IF NOT EXISTS iot_tsdb_raw.st_event (ts TIMESTAMP, event_id NCHAR(64), event_name NCHAR(64), severity INT, code NCHAR(32), payload JSON) TAGS (device_id NCHAR(64), station_id NCHAR(32), enterprise_id NCHAR(32), product_key NCHAR(64))")" != 0 ]; then
  log "[2/6] 注意: st_event 未创建（payload JSON 普通列不被 TDengine 支持，G-2 见报告 §G-2）——压测主路径不产事件，不影响基线"
fi
info "[2/6] TDengine 就绪（库 + st_prop_snd_ess_pcs）"

# ---------------------------------------------------------------
# 2. seed 16000 台设备（幂等 INSERT IGNORE）
# ---------------------------------------------------------------
log "[3/6] seed 16000 台设备（snd_ess_pcs）..."
stress seed --count 16000 --product snd_ess_pcs --secret-base sanduo-stress \
  --user root --password "${MYSQL_PASSWORD:-}" \
  > "${RESULTS_DIR}/seed.log" 2>&1 || die "seed 失败，见 ${RESULTS_DIR}/seed.log"
info "[3/6] seed 完成"

# ---------------------------------------------------------------
# 3. 四档吞吐压测（4万→8万→16万→32万 msg/s；每档前后快照）
# ---------------------------------------------------------------
log "[4/6] 四档吞吐压测（每档 ${DURATION}s，判停找峰值）..."
TIERS=(2000 4000 8000 16000)   # 台数 × 20 msg/s
RATE=20
LAG_GROUPS="energy-access-uplink energy-tsdb-prop energy-cmd-ack"

snapshot() { # 输出: 当前时点各观测值（一行）
  local lag=0 g
  for g in $LAG_GROUPS; do
    lag=$((lag + $(kafka_group_lag_sum "$g" 2>/dev/null || echo 0)))
  done
  local msgin
  msgin=$(broker_field messagesIn 2>/dev/null || echo 0)
  local rows
  rows=$(td_count "iot_tsdb_raw.st_prop_snd_ess_pcs" 2>/dev/null || echo 0)
  printf "%s msgin=%s rows=%s lag=%s\n" "$(date +%H:%M:%S)" "${msgin:-0}" "${rows:-0}" "${lag:-0}"
}

prev_avg=0
peak_tier=0
summary_rows=()
for ((t = 0; t < MAX_TIERS; t++)); do
  count=${TIERS[$t]}
  tier=$((t + 1))
  log "  ── 档 ${tier}: ${count} 台 × ${RATE} msg/s = $((count * RATE)) msg/s（${DURATION}s）──"
  before="$(snapshot)"
  info "     before: ${before}"
  if ! stress throughput --count "$count" --rate "$RATE" --duration "$DURATION" \
      > "${RESULTS_DIR}/tier-${tier}.log" 2>&1; then
    info "     ⚠ 档 ${tier} stress 非零退出（见 tier-${tier}.log），继续下一档"
  fi
  after="$(snapshot)"
  info "     after : ${after}"

  # 解析 stress 输出（ThroughputLoad 汇总块）
  avg=$(grep -oE '平均吞吐[[:space:]]*: [0-9]+' "${RESULTS_DIR}/tier-${tier}.log" | grep -oE '[0-9]+' | head -1)
  p50=$(grep -oE 'P50=[0-9]+' "${RESULTS_DIR}/tier-${tier}.log" | head -1 | cut -d= -f2)
  p95=$(grep -oE 'P95=[0-9]+' "${RESULTS_DIR}/tier-${tier}.log" | head -1 | cut -d= -f2)
  p99=$(grep -oE 'P99=[0-9]+' "${RESULTS_DIR}/tier-${tier}.log" | head -1 | cut -d= -f2)
  pub=$(grep -oE '累计上报[[:space:]]*: [0-9]+' "${RESULTS_DIR}/tier-${tier}.log" | grep -oE '[0-9]+' | head -1)
  fail=$(grep -oE '发布失败[[:space:]]*: [0-9]+' "${RESULTS_DIR}/tier-${tier}.log" | grep -oE '[0-9]+' | head -1)
  avg=${avg:-0}; p50=${p50:-0}; p95=${p95:-0}; p99=${p99:-0}; pub=${pub:-0}; fail=${fail:-0}

  info "     档 ${tier} 结果: avg=${avg} P50=${p50} P95=${p95} P99=${p99} 发布=${pub} 失败=${fail}"

  # 判停：增幅 <15% 或失败率 >0.1%
  gain=100000
  if [ "$prev_avg" -gt 0 ]; then
    gain=$(awk -v a="$avg" -v p="$prev_avg" 'BEGIN{printf "%.1f", (p>0)? (a-p)*100/p : 100000}')
    info "     吞吐增幅: ${gain}%（上档 ${prev_avg}）"
  fi
  if [ "$fail" -gt 0 ] && [ "$pub" -gt 0 ]; then
    failrate=$(awk -v f="$fail" -v p="$pub" 'BEGIN{printf "%.3f", f*100/p}')
    info "     失败率: ${failrate}%"
    if [ "${failrate%.*}" -ge 1 ] || awk -v f="$failrate" 'BEGIN{exit !(f > 0.1)}'; then
      info "     ⚠ 失败率 >0.1%，判该档为峰值档，停止升档"
      peak_tier=$tier; break
    fi
  fi
  if [ "$prev_avg" -gt 0 ] && [ "$avg" -gt 0 ] && awk -v g="$gain" 'BEGIN{exit !(g < 15)}'; then
    info "     ⚠ 吞吐增幅 <15%，判该档为峰值档，停止升档"
    peak_tier=$tier; break
  fi
  prev_avg=$avg
  summary_rows+=("{\"tier\":${tier},\"count\":${count},\"rate\":${RATE},\"avg\":${avg},\"p50\":${p50},\"p95\":${p95},\"p99\":${p99},\"published\":${pub},\"failed\":${fail},\"before\":\"${before}\",\"after\":\"${after}\"}")
  if [ "$tier" -eq "$MAX_TIERS" ]; then peak_tier=$tier; fi
done

if [ "$peak_tier" -eq 0 ]; then peak_tier="$tier"; fi
info "[4/6] 峰值档: 档 ${peak_tier}（avg=${avg} msg/s）"

# ---------------------------------------------------------------
# 4. 演练 05：控制链路 P99
# ---------------------------------------------------------------
log "[5/6] 演练 05：控制链路 P99（P99 ≤ 500ms）..."
"${ROOT}/test/drill/05-command-p99.sh" > "${RESULTS_DIR}/drill-05.log" 2>&1
DRILL05_RC=$?
cat "${RESULTS_DIR}/drill-05.log"
P99_CTRL=$(grep -oE 'P99=[0-9]+' "${RESULTS_DIR}/drill-05.log" | head -1 | cut -d= -f2)
P99_CTRL=${P99_CTRL:-N/A}
if [ "$P99_CTRL" = "N/A" ]; then P99_CTRL_JSON=null; else P99_CTRL_JSON=$P99_CTRL; fi
echo "${P99_CTRL}" > "${RESULTS_DIR}/control-p99.txt"
info "[5/6] 控制链路 P99=${P99_CTRL}ms（drill exit=${DRILL05_RC}）"

# ---------------------------------------------------------------
# 4b. 演练 01/02（03/04 受限不执行，报告标注）
# ---------------------------------------------------------------
log "[5b/6] 演练 01：Kafka 重平衡 ..."
"${ROOT}/test/drill/01-kafka-rebalance.sh" > "${RESULTS_DIR}/drill-01.log" 2>&1
DRILL01_RC=$?
info "  演练 01 exit=${DRILL01_RC}"

log "[5c/6] 演练 02：Broker 重启自愈 ..."
"${ROOT}/test/drill/02-broker-restart.sh" > "${RESULTS_DIR}/drill-02.log" 2>&1
DRILL02_RC=$?
info "  演练 02 exit=${DRILL02_RC}"

# ---------------------------------------------------------------
# 5. 汇总 summary.json
# ---------------------------------------------------------------
log "[6/6] 写汇总 ${RESULTS_DIR}/summary.json ..."
{
  echo "{"
  echo "  \"runId\": \"${RUN_ID}\","
  echo "  \"maxTiers\": ${MAX_TIERS},"
  echo "  \"durationSec\": ${DURATION},"
  echo "  \"peakTier\": ${peak_tier},"
  echo "  \"controlP99Ms\": ${P99_CTRL_JSON},"
  echo "  \"drills\": {"
  echo "    \"05\": \"exit=${DRILL05_RC}\","
  echo "    \"01\": \"exit=${DRILL01_RC}\","
  echo "    \"02\": \"exit=${DRILL02_RC}\","
  echo "    \"03\": \"受限未执行（需 docker-redis）\","
  echo "    \"04\": \"受限未执行（需管理员停 MySQL）\""
  echo "  },"
  echo "  \"tiers\": ["
  first=1
  for row in "${summary_rows[@]:-}"; do
    [ -n "$row" ] || continue
    [ "$first" -eq 0 ] && echo ","
    printf "    %s" "$row"
    first=0
  done
  echo ""
  echo "  ]"
  echo "}"
} > "${RESULTS_DIR}/summary.json"
log "===== run-baseline 完成（${RUN_ID}）====="
