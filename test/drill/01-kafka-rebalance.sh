#!/usr/bin/env bash
# =====================================================================
# 演练 01：Kafka 消费组重平衡（Rebalance）
# 验证点：
#   1. 平台消费组健康（成员在册、分区全部归属）；
#   2. 上行突发积压可在消费端跟上（LAG 归零）；
#   3. 重启 energy-access（其 4 线程消费组离开→重平衡→归位）后
#      分区重新分配、LAG 依旧归零，消息不丢。
# 前置：Kafka 容器 up、Broker up、设备已 seed、energy-access 运行。
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/lib.sh"

log "========== 演练 01：Kafka 消费组重平衡 =========="

# ---- 前置检查 ----
if ! docker_ready; then
  die "Docker daemon 不可用（Kafka 依赖容器，请先启动 Docker）"
fi
if ! compose ps kafka 2>/dev/null | grep -q Up; then
  die "Kafka 容器未运行（docker compose up -d）"
fi
if ! port_open 127.0.0.1 1883; then
  die "Broker 未运行（energy-mqtt-broker）"
fi
if [ -z "$(service_pid energy-access)" ]; then
  die "energy-access 未运行——重平衡演练需要真实消费组"
fi
if [ ! -f "$STRESS_JAR" ]; then
  die "缺少压测工具 $STRESS_JAR"
fi

GROUP="energy-access-uplink"
TOPIC_LAG_THRESHOLD=100

# ---- 1. 消费组在册 ----
log "[Drill] 检查消费组 $GROUP ..."
if ! kafka_group_describe "$GROUP" 2>/dev/null | grep -q "$GROUP"; then
  die "消费组 $GROUP 不存在，请确认 energy-access 已启动并消费 ${MQTT_ROUTER_TOPIC:-mqtt.router}"
fi
ok "消费组 $GROUP 存在"

# ---- 2. 基线：分区归属完整（无未分配分区）----
log "[Drill] 基线描述 $GROUP："
kafka_group_describe "$GROUP" | sed -n '1,12p'
UNASSIGNED=$(kafka_group_describe "$GROUP" | tail -n +2 | awk '$6=="-" {n++} END {print n+0}')
if [ "${UNASSIGNED:-0}" -gt 0 ]; then
  err "存在 $UNASSIGNED 个未分配分区（消费端异常）"
else
  ok "分区全部归属消费者（未分配分区数 = 0）"
fi

# ---- 3. 突发上行，验证 LAG 归零 ----
log "[Drill] 注入 200 设备 × 20 msg/s × 30s 上行突发 ..."
stress throughput --count 200 --rate 20 --duration 30 --workers 8 --io-threads 8 \
  >"${LOG_DIR}/01-burst.log" 2>&1 || true
grep -E "吞吐|目标" "${LOG_DIR}/01-burst.log" | tail -3

log "[Drill] 等待消费端追赶（LAG 归零）..."
i=0
while (( i < 60 )); do
  LAG=$(kafka_group_lag_sum "$GROUP")
  if [ "${LAG:-0}" -le "$TOPIC_LAG_THRESHOLD" ]; then
    break
  fi
  sleep 5
  i=$((i + 5))
done
if [ "${LAG:-0}" -gt "$TOPIC_LAG_THRESHOLD" ]; then
  err "突发后 LAG 未归零：$LAG（消费端跟不上或消息丢失）"
else
  ok "突发后消费组追赶完成（LAG=$LAG ≤ $TOPIC_LAG_THRESHOLD）"
fi

# ---- 4. 实时重平衡：重启 energy-access，观察消费组归位 ----
log "[Drill] 重启 energy-access 触发消费组重平衡 ..."
BEFORE=$(kafka_group_describe "$GROUP" | tail -n +2 | grep -c "$GROUP" || true)
stop_service energy-access
# 消费端离开后：分区标记为未归属（Consumer 列为 -），LAG 开始积压
i=0
while (( i < 40 )); do
  D=$(kafka_group_describe "$GROUP")
  if echo "$D" | tail -n +2 | grep -q 'CONSUMER.*-'; then
    info "[Drill] 检测到消费端离线（分区未归属）"
    break
  fi
  if ! echo "$D" | grep -q "$GROUP"; then
    info "[Drill] 消费组进入 Empty/Dead 状态"
    break
  fi
  sleep 2
  i=$((i + 2))
done

start_service energy-access
info "[Drill] 等待消费端重平衡并重新归属分区 ..."
i=0
while (( i < 90 )); do
  UNASSIGNED=$(kafka_group_describe "$GROUP" | tail -n +2 | awk '$6=="-" {n++} END {print n+0}')
  if [ "${UNASSIGNED:-0}" -eq 0 ]; then
    break
  fi
  sleep 3
  i=$((i + 3))
done
AFTER_UNASSIGNED=$(kafka_group_describe "$GROUP" | tail -n +2 | awk '$6=="-" {n++} END {print n+0}')
if [ "${AFTER_UNASSIGNED:-1}" -gt 0 ]; then
  err "重平衡后仍有未分配分区：$AFTER_UNASSIGNED（原分区数基线含 $BEFORE 行）"
else
  ok "重平衡完成：消费组重新接管全部分区"
fi

# ---- 5. 重平衡后再次突发，验证 LAG 归零（消息不丢）----
log "[Drill] 重平衡后二次突发（200×20×20s）..."
stress throughput --count 200 --rate 20 --duration 20 --workers 8 --io-threads 8 \
  >"${LOG_DIR}/01-burst2.log" 2>&1 || true
i=0
while (( i < 60 )); do
  LAG2=$(kafka_group_lag_sum "$GROUP")
  if [ "${LAG2:-0}" -le "$TOPIC_LAG_THRESHOLD" ]; then
    break
  fi
  sleep 5
  i=$((i + 5))
done
if [ "${LAG2:-0}" -gt "$TOPIC_LAG_THRESHOLD" ]; then
  err "重平衡后 LAG 未归零：$LAG2"
else
  ok "重平衡后消费继续追平（LAG=$LAG2）——消息无丢失"
fi

summary
exit "$FAIL"
