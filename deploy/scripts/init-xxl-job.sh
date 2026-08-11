#!/usr/bin/env bash
# =====================================================================
# 初始化 xxl-job 调度中心数据库（幂等，可重复执行）
#
#  步骤：
#   1. 确保 xxl_job 库存在（CREATE DATABASE IF NOT EXISTS）
#   2. 若 xxl_job_info 表不存在 → 导入官方 schema（deploy/sql/xxl_job_init.sql）
#   3. 清理 Task 6 遗留的 EMS 占位任务行（DELETE ... WHERE job_desc LIKE '占位·%'，幂等）
#   4. 注册 6 个 energy-* 执行器组 + 7 个低频任务（幂等：INSERT..SELECT..WHERE NOT EXISTS）
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
echo "[init-xxl-job] 1/4 确保数据库 ${DB_NAME} 存在"
mysql_query "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ---------------------------------------------------------------------
# 2. 表不存在才导入官方 schema（官方建表语句非 IF NOT EXISTS，故必须先判断）
# ---------------------------------------------------------------------
TABLE_CNT="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_name='xxl_job_info';")"
if [ "${TABLE_CNT:-0}" = "0" ]; then
  echo "[init-xxl-job] 2/4 ${DB_NAME}.xxl_job_info 不存在，导入官方 schema：${SQL_FILE}"
  mysql_run_file "$SQL_FILE"
else
  echo "[init-xxl-job] 2/4 ${DB_NAME}.xxl_job_info 已存在，跳过 schema 导入（幂等）"
fi

# ---------------------------------------------------------------------
# 3. 清理 Task 6 遗留的 EMS 占位任务行（job_desc 前缀 '占位·'，幂等）
#    说明：预登记占位行为 Task 6 越界产物——handler 名（ems*Placeholder）与
#         Task 8 真实任务（emsDailyPlanGenerate/emsExecutionRetentionClean 等）错位，
#         且 job_group=1 指向示例执行器，会污染调度中心控制台（死行）。
#         此处只做清理；真实 EMS 任务由第 4 步统一注册。
# ---------------------------------------------------------------------
echo "[init-xxl-job] 3/4 清理 Task 6 遗留 EMS 占位任务行（job_desc LIKE '占位·%'）"
mysql_query "DELETE FROM \`${DB_NAME}\`.xxl_job_info WHERE job_desc LIKE '占位·%';" >/dev/null

# ---------------------------------------------------------------------
# 4. 注册 6 个 energy-* 执行器组与 7 个低频任务（幂等，可重复执行）
#    执行器组：xxl_job_group 官方 schema 不预置 energy-* 组（admin 不会自动建组），
#              此处幂等补齐。address_type=0（自动注册）、address_list 留空，
#              由各服务启动后按 app_name 自动上报在线地址。
#              注意：xxl_job_group.app_name 无唯一键，故用 NOT EXISTS 防重而非 INSERT IGNORE。
#    任务：7 个（每日计划生成 1 + 数据清理 6），job_group 通过 JOIN xxl_job_group 按
#          app_name 动态取 id（不硬编码）；executor_handler 与 Java @XxlJob 注解名一致；
#          glue_type=BEAN、executor_block_strategy=SERIAL_EXECUTION、
#          trigger_status=0（停用，避免脚本执行后立即误触发，用户在控制台手动启用）。
#    幂等：任务行以 executor_handler 存在性判断（xxl_job_info 无唯一键）。
# ---------------------------------------------------------------------
echo "[init-xxl-job] 4/4 注册 6 个 energy-* 执行器组与 7 个低频任务"

# ---- 4.1 注册执行器组 ----
echo "[init-xxl-job]    - 执行器组 energy-ems（储能EMS）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'energy-ems', '储能EMS', 0, NULL, NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-ems');" >/dev/null
echo "[init-xxl-job]    - 执行器组 energy-command（指令服务）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'energy-command', '指令服务', 0, NULL, NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-command');" >/dev/null
echo "[init-xxl-job]    - 执行器组 energy-device（设备服务）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'energy-device', '设备服务', 0, NULL, NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-device');" >/dev/null
echo "[init-xxl-job]    - 执行器组 energy-shadow（影子服务）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'energy-shadow', '影子服务', 0, NULL, NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-shadow');" >/dev/null
echo "[init-xxl-job]    - 执行器组 energy-system（系统服务）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'energy-system', '系统服务', 0, NULL, NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-system');" >/dev/null
echo "[init-xxl-job]    - 执行器组 energy-alarm（告警服务）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'energy-alarm', '告警服务', 0, NULL, NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-alarm');" >/dev/null

# ---- 记住各组 id（供任务注册校验与最终回显）----
EMS_GROUP_ID="$(mysql_query "SELECT id FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-ems';")"
COMMAND_GROUP_ID="$(mysql_query "SELECT id FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-command';")"
DEVICE_GROUP_ID="$(mysql_query "SELECT id FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-device';")"
SHADOW_GROUP_ID="$(mysql_query "SELECT id FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-shadow';")"
SYSTEM_GROUP_ID="$(mysql_query "SELECT id FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-system';")"
ALARM_GROUP_ID="$(mysql_query "SELECT id FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name='energy-alarm';")"

# ---- 4.2 注册 7 个任务（job_group 动态取自执行器组表，不硬编码 id）----
echo "[init-xxl-job]    - 任务 emsDailyPlanGenerate（每日生成次日充放电计划，00:05）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '每日生成次日充放电计划', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 5 0 * * *',
   'DO_NOTHING', 'FIRST', 'emsDailyPlanGenerate', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-ems'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='emsDailyPlanGenerate');" >/dev/null
echo "[init-xxl-job]    - 任务 emsExecutionRetentionClean（执行记录数据清理，03:30）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '执行记录数据清理', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 30 3 * * *',
   'DO_NOTHING', 'FIRST', 'emsExecutionRetentionClean', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-ems'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='emsExecutionRetentionClean');" >/dev/null
echo "[init-xxl-job]    - 任务 commandRetentionClean（指令记录数据清理，03:30）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '指令记录数据清理', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 30 3 * * *',
   'DO_NOTHING', 'FIRST', 'commandRetentionClean', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-command'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='commandRetentionClean');" >/dev/null
echo "[init-xxl-job]    - 任务 deviceOnlineRetentionClean（设备在线记录数据清理，03:30）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '设备在线记录数据清理', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 30 3 * * *',
   'DO_NOTHING', 'FIRST', 'deviceOnlineRetentionClean', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-device'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='deviceOnlineRetentionClean');" >/dev/null
echo "[init-xxl-job]    - 任务 shadowHistoryRetentionClean（影子历史数据清理，03:30）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '影子历史数据清理', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 30 3 * * *',
   'DO_NOTHING', 'FIRST', 'shadowHistoryRetentionClean', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-shadow'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='shadowHistoryRetentionClean');" >/dev/null
echo "[init-xxl-job]    - 任务 systemOperatorLogRetentionClean（系统操作日志数据清理，03:30）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '系统操作日志数据清理', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 30 3 * * *',
   'DO_NOTHING', 'FIRST', 'systemOperatorLogRetentionClean', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-system'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='systemOperatorLogRetentionClean');" >/dev/null
echo "[init-xxl-job]    - 任务 alarmRetentionClean（告警记录数据清理，03:30）"
mysql_query "INSERT INTO \`${DB_NAME}\`.xxl_job_info
  (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
   misfire_strategy, executor_route_strategy, executor_handler, executor_param,
   executor_block_strategy, executor_timeout, executor_fail_retry_count,
   glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
   trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '告警记录数据清理', NOW(), NOW(), 'EnergyX', '', 'CRON', '0 30 3 * * *',
   'DO_NOTHING', 'FIRST', 'alarmRetentionClean', '',
   'SERIAL_EXECUTION', 0, 0,
   'BEAN', '', '', NOW(), '',
   0, 0, 0
FROM \`${DB_NAME}\`.xxl_job_group g
WHERE g.app_name='energy-alarm'
  AND NOT EXISTS (SELECT 1 FROM \`${DB_NAME}\`.xxl_job_info i WHERE i.executor_handler='alarmRetentionClean');" >/dev/null

# ---------------------------------------------------------------------
# 验证：统计执行器组与任务数
# ---------------------------------------------------------------------
GROUP_CNT="$(mysql_query "SELECT COUNT(*) FROM \`${DB_NAME}\`.xxl_job_group WHERE app_name LIKE 'energy-%';")"
REAL_TASK_CNT="$(mysql_query "SELECT COUNT(*) FROM \`${DB_NAME}\`.xxl_job_info
WHERE executor_handler IN ('emsDailyPlanGenerate','emsExecutionRetentionClean','commandRetentionClean',
'deviceOnlineRetentionClean','shadowHistoryRetentionClean','systemOperatorLogRetentionClean','alarmRetentionClean');")"
TOTAL="$(mysql_query "SELECT COUNT(*) FROM \`${DB_NAME}\`.xxl_job_info;")"
echo "[init-xxl-job] 完成：energy-* 执行器组 ${GROUP_CNT} 个（id：ems=${EMS_GROUP_ID} command=${COMMAND_GROUP_ID} device=${DEVICE_GROUP_ID} shadow=${SHADOW_GROUP_ID} system=${SYSTEM_GROUP_ID} alarm=${ALARM_GROUP_ID}）"
echo "[init-xxl-job] 完成：7 个低频任务已注册 ${REAL_TASK_CNT} 个；${DB_NAME}.xxl_job_info 共 ${TOTAL} 个任务（官方演示 1 + 本脚本 7）"
echo "[init-xxl-job] 下一步：docker compose up -d xxl-job-admin 并访问 http://127.0.0.1:8099/xxl-job-admin"
