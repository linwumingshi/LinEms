#!/usr/bin/env bash
# EnergyX · 子项目B 造数：TDengine 属性宽表 st_prop_snd_ess_pcs / dev_8000000000000000001
# 前置：ems-tdengine 容器运行（6041 REST 可访问）；已执行过 ALTER STABLE ADD COLUMN runMode。
# 用法：bash sql/tdengine/seed_props.sh
set -euo pipefail

BASE='http://127.0.0.1:6041/rest/sql'
AUTH='root:taosdata'
DB='iot_tsdb_raw'
CHILD='dev_8000000000000000001'
STABLE='st_prop_snd_ess_pcs'

# 1) 确保子表存在（已存在则 no-op；直接 INSERT 子表复用其既有 TAGS）
curl -s -u "$AUTH" \
  -d "CREATE TABLE IF NOT EXISTS $DB.$CHILD USING $DB.$STABLE TAGS ('8000000000000000001','','','snd_ess_pcs')" \
  "$BASE" >/dev/null

# 2) 造数：近 24h 按小时 + 最近 5 分钟逐分钟（数值平滑漂移便于看曲线；runMode=1）
now_ms=$(( $(date +%s) * 1000 ))
rows=''
append() { rows="${rows:+$rows, }$1"; }

for i in $(seq 0 23); do
  ts=$(( now_ms - i * 3600000 ))
  soc=$(awk "BEGIN{printf \"%.1f\", 86.0 - $i * 1.0}")
  voltage=$(awk "BEGIN{printf \"%.1f\", 204.0 + ($i % 5) * 0.5}")
  current=$((18 + i % 3))
  power=$((1031 + i * 10))
  temp=$(awk "BEGIN{printf \"%.1f\", 34.0 + ($i % 4)}")
  append "($ts, 'seed-$ts', 'report', $soc, $voltage, $current, $power, $temp, 1)"
done
for j in 0 1 2 3 4; do
  ts=$(( now_ms - j * 60000 ))
  soc=$(awk "BEGIN{printf \"%.1f\", 86.0 - $j * 0.2}")
  append "($ts, 'seed-$ts', 'report', $soc, 204.0, 18, 1031, 34.0, 1)"
done

sql="INSERT INTO $DB.$CHILD (ts, msg_id, data_type, soc, voltage, current, power, temp, \`runMode\`) VALUES $rows"
echo "==> seed $(printf '%s' "$rows" | tr ',' '\n' | wc -l | tr -d ' ') 行"
curl -s -u "$AUTH" -d "$sql" "$BASE"
echo
