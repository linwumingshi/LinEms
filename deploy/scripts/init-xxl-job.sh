#!/usr/bin/env bash
# =====================================================================
# 初始化 xxl-job 调度中心数据库（幂等，可重复执行）
#
#  步骤：
#   1. 确保 xxl_job 库存在（CREATE DATABASE IF NOT EXISTS）
#   2. 若 xxl_job_info 表不存在 → 导入官方 schema（deploy/sql/xxl_job_init.sql）
#   3. 预登记 7 个 EMS 占位任务行（INSERT IGNORE，重复执行无副作用）
#
#  连接：默认本机 MySQL 127.0.0.1:3306，root/root&QAQ
#        可用 MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD 环境变量覆盖
#  客户端：优先本机 mysql CLI → Node mysql2（NODE_BIN/NODE_MODULES 可覆盖）
#          → Docker mysql:8.0 临时容器（容器内经 host.docker.internal 连宿主 MySQL）
#
#  用法：bash deploy/scripts/init-xxl-job.sh
# =====================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SQL_FILE="${ROOT}/deploy/sql/xxl_job_init.sql"

# ---- 连接参数（密码含 & 字符，全程经环境变量传递、不做 shell 拼接，规避转义问题）----
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root&QAQ}"
DB_NAME="xxl_job"

# ---- Node/mysql2 受管路径（可被环境变量覆盖）----
NODE_BIN="${NODE_BIN:-C:/Users/linwe/.workbuddy/binaries/node/versions/22.22.2/node.exe}"
NODE_MODULES="${NODE_MODULES:-C:/Users/linwe/.workbuddy/binaries/node/workspace/node_modules}"

# ---- 探测可用的 MySQL 客户端 ----
MYSQL_CLIENT=""
if command -v mysql >/dev/null 2>&1; then
  MYSQL_CLIENT="cli"
elif [ -x "$NODE_BIN" ] && [ -d "$NODE_MODULES" ] && [ -f "${NODE_MODULES}/mysql2/package.json" ]; then
  MYSQL_CLIENT="node"
elif command -v docker >/dev/null 2>&1 && docker image inspect mysql:8.0 >/dev/null 2>&1; then
  MYSQL_CLIENT="docker"
else
  echo "[init-xxl-job] 未找到可用 MySQL 客户端（mysql CLI / Node mysql2 / Docker mysql:8.0）" >&2
  exit 1
fi
echo "[init-xxl-job] MySQL 客户端: ${MYSQL_CLIENT}  (${MYSQL_HOST}:${MYSQL_PORT}/${DB_NAME})"

# ---- Node 执行器：helper 代码经 node -e 内联运行（不落盘临时文件，避免清理负担）----
node_runner() { # 子命令 参数
  local sub arg js
  sub="$1"; arg="${2:-}"
  read -r -d '' js <<'NODE_EOF' || true
// xxl-job 初始化辅助逻辑（node -e 内联执行）
const fs = require('fs');
const mysql = require('mysql2/promise');
const [sub, arg] = process.argv.slice(1);
const cfg = {
  host: process.env.MYSQL_HOST,
  port: Number(process.env.MYSQL_PORT),
  user: process.env.MYSQL_USER,
  password: process.env.MYSQL_PASSWORD,
  multipleStatements: true,
};
(async () => {
  const conn = await mysql.createConnection(cfg);
  try {
    if (sub === 'file') {
      await conn.query(fs.readFileSync(arg, 'utf8'));
      console.log(`[init-xxl-job][node] 已执行 SQL 文件 ${arg}`);
    } else if (sub === 'query') {
      const [rows] = await conn.query(arg);
      if (Array.isArray(rows)) {
        rows.forEach((r) => console.log(Object.values(r)[0]));
      } else {
        console.log('OK'); // DDL/DML 无行集返回
      }
    } else {
      throw new Error('unknown subcommand: ' + sub);
    }
  } catch (e) {
    console.error('[init-xxl-job][node] ' + e.message);
    process.exit(1);
  } finally {
    await conn.end();
  }
})();
NODE_EOF
  NODE_PATH="$NODE_MODULES" \
  MYSQL_HOST="$MYSQL_HOST" MYSQL_PORT="$MYSQL_PORT" \
  MYSQL_USER="$MYSQL_USER" MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    "$NODE_BIN" -e "$js" "$sub" "$arg"
}

# ---- 执行单条 SQL（结果输出到 stdout，供条件判断）----
mysql_query() { # SQL
  case "$MYSQL_CLIENT" in
    cli)    MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" --batch --skip-column-names -e "$1" ;;
    node)   node_runner query "$1" ;;
    docker) MYSQL_PWD="$MYSQL_PASSWORD" docker run -i --rm -e MYSQL_PWD mysql:8.0 \
              mysql -hhost.docker.internal -P"$MYSQL_PORT" -u"$MYSQL_USER" --batch --skip-column-names -e "$1" ;;
  esac
}

# ---- 执行 SQL 文件 ----
mysql_run_file() { # 文件路径
  case "$MYSQL_CLIENT" in
    cli)    MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" < "$1" ;;
    node)   node_runner file "$(cygpath -w "$1")" ;;
    docker) MYSQL_PWD="$MYSQL_PASSWORD" docker run -i --rm -e MYSQL_PWD mysql:8.0 \
              mysql -hhost.docker.internal -P"$MYSQL_PORT" -u"$MYSQL_USER" < "$1" ;;
  esac
}

# ---------------------------------------------------------------------
# 1. 确保数据库存在
# ---------------------------------------------------------------------
echo "[init-xxl-job] 1/3 确保数据库 ${DB_NAME} 存在"
mysql_query "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ---------------------------------------------------------------------
# 2. 表不存在才导入官方 schema（官方建表语句非 IF NOT EXISTS，故必须先判断）
# ---------------------------------------------------------------------
TABLE_CNT="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_name='xxl_job_info';")"
if [ "${TABLE_CNT:-0}" = "0" ]; then
  echo "[init-xxl-job] 2/3 ${DB_NAME}.xxl_job_info 不存在，导入官方 schema：${SQL_FILE}"
  mysql_run_file "$SQL_FILE"
else
  echo "[init-xxl-job] 2/3 ${DB_NAME}.xxl_job_info 已存在，跳过 schema 导入（幂等）"
fi

# ---------------------------------------------------------------------
# 3. 预登记 7 个 EMS 占位任务行（INSERT IGNORE，幂等）
#    说明：schedule_type=NONE 不触发调度；具体 handler/调度策略由后续任务绑定
# ---------------------------------------------------------------------
echo "[init-xxl-job] 3/3 预登记 7 个 EMS 占位任务行（INSERT IGNORE）"
mysql_query "
INSERT IGNORE INTO \`${DB_NAME}\`.xxl_job_info
  (id, job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid)
VALUES
  (2,  1, '占位·设备状态同步（Task6 预登记，待绑定）', NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsDeviceStatusSyncPlaceholder', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), ''),
  (3,  1, '占位·遥测数据聚合（Task6 预登记，待绑定）', NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsTelemetryAggPlaceholder',     '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), ''),
  (4,  1, '占位·告警检测（Task6 预登记，待绑定）',    NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsAlarmDetectPlaceholder',     '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), ''),
  (5,  1, '占位·电站健康巡检（Task6 预登记，待绑定）', NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsStationHealthPlaceholder',   '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), ''),
  (6,  1, '占位·报表生成（Task6 预登记，待绑定）',    NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsReportGenPlaceholder',       '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), ''),
  (7,  1, '占位·数据保留清理（Task6 预登记，待绑定）', NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsRetentionCleanPlaceholder',  '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), ''),
  (8,  1, '占位·充放电计划（Task6 预登记，待绑定）',  NOW(), NOW(), 'EMS', '', 'NONE', NULL, 'DO_NOTHING', NULL, 'emsEnergyPlanPlaceholder',      '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '预登记任务', NOW(), '');
"

# 修正官方 schema 自带笔误（glue_type 官方 INSERT 误写 BEAM，正确枚举为 BEAN；幂等）
mysql_query "UPDATE \`${DB_NAME}\`.xxl_job_info SET glue_type='BEAN' WHERE glue_type='BEAM';" >/dev/null

# ---------------------------------------------------------------------
# 验证：统计任务数
# ---------------------------------------------------------------------
TOTAL="$(mysql_query "SELECT COUNT(*) FROM \`${DB_NAME}\`.xxl_job_info;")"
echo "[init-xxl-job] 完成：${DB_NAME}.xxl_job_info 共 ${TOTAL} 个任务（官方演示 1 + EMS 占位 7）"
echo "[init-xxl-job] 下一步：docker compose up -d xxl-job-admin 并访问 http://127.0.0.1:8099/xxl-job-admin"
