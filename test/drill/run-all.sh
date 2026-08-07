#!/usr/bin/env bash
# =====================================================================
# 故障演练总入口：按依赖序运行 01→05。
# 每个演练独立计数 PASS/FAIL，全部结束后汇总。
# 用法：
#   ./run-all.sh                # 顺序执行全部（04 为预演模式）
#   ./run-all.sh --execute      # 04 执行真实 MySQL 停服演练（需管理员权限）
#   ./run-all.sh 03             # 只跑 03
# =====================================================================
set -uo pipefail
source "$(dirname "$0")/lib.sh"

MODE=""
FILTER=""
for a in "$@"; do
  case "$a" in
    --execute) MODE="--execute" ;;
    [0-9][0-9]) FILTER="$a" ;;
    *) info "[run-all] 忽略未知参数: $a" ;;
  esac
done

DRILLS=("01-kafka-rebalance" "02-broker-restart" "03-redis-degrade"
        "04-mysql-failover" "05-command-p99")

log "========== EnergyX 平台故障演练套件 =========="
info "[run-all] 演练目录: $DRILL_DIR"
info "[run-all] 日志目录: $LOG_DIR"
if [ -n "$MODE" ]; then
  info "[run-all] 模式: $MODE"
fi

TOTAL_PASS=0
TOTAL_FAIL=0
RUN=0
FAILED_DRILLS=""

for name in "${DRILLS[@]}"; do
  if [ -n "$FILTER" ] && [[ "$name" != ${FILTER}* ]]; then
    continue
  fi
  script="${DRILL_DIR}/${name}.sh"
  if [ ! -f "$script" ]; then
    err "[run-all] 缺少脚本 $script"
    continue
  fi
  RUN=$((RUN + 1))
  log "==========================================================="
  log "  开始演练: ${name}"
  log "==========================================================="
  bash "$script" $MODE
  rc=$?
  if [ $rc -eq 0 ]; then
    info "[run-all] ${name} 完成（演练级 PASS）"
  else
    err "[run-all] ${name} 未通过（exit=$rc）"
    FAILED_DRILLS="${FAILED_DRILLS} ${name}"
  fi
  echo
done

log "========== 演练套件汇总 =========="
printf '  执行演练数: %d\n' "$RUN"
if [ "$RUN" -eq 0 ]; then
  err "未匹配到任何演练（过滤器: ${FILTER:-无}）"
  exit 1
fi
if [ -n "$FAILED_DRILLS" ]; then
  err "未通过演练:${FAILED_DRILLS}"
  exit 1
fi
ok "全部演练通过 ✅"
exit 0
