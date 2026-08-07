#!/usr/bin/env bash
# =====================================================================
# 初始化 Nacos 配置中心密钥 dataId：energy-shared.yaml（group ENERGY）
# 读取 deploy/env/local.env 的密钥值 → 组装 YAML → 登录 Nacos 取 accessToken → 推送（幂等覆盖）
# 用法：bash deploy/scripts/init-nacos-config.sh
# =====================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROOT}/deploy/env/local.env"

# 1. 加载密钥值（缺失即 fail-fast）
[ -f "$ENV_FILE" ] || { echo "[init-nacos] 缺少 $ENV_FILE（先 cp local.env.example local.env 并填值）" >&2; exit 1; }
source "$ENV_FILE"

NACOS_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_GROUP="${NACOS_GROUP:-ENERGY}"
DATA_ID="energy-shared.yaml"

# 2. 登录取 accessToken
TOKEN="$(curl -s -m 10 -X POST "http://${NACOS_ADDR}/nacos/v1/auth/users/login" \
  -d "username=${NACOS_USERNAME}&password=${NACOS_PASSWORD}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
[ -n "$TOKEN" ] || { echo "[init-nacos] Nacos 登录失败（检查 NACOS_USERNAME/NACOS_PASSWORD）" >&2; exit 1; }

# 3. 组装 content（值由 env 展开注入，脚本零硬编码密钥；全 ASCII 故内联 url-encode 安全）
CONTENT="\
# P0-4 secrets dataId (pushed by init-nacos-config.sh; do not edit manually)
spring:
  datasource:
    password: ${MYSQL_PASSWORD}
energyx:
  jwt:
    secret: ${JWT_SECRET}
  tsdb:
    jdbc-password: ${TDENGINE_PASSWORD}
  ems:
    device-name: ${EMS_DEVICE_NAME:-sim-dev-000001}
"

# 4. 推送（--data-urlencode 自动 url-encode 多行值）
HTTP_CODE="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST \
  "http://${NACOS_ADDR}/nacos/v1/cs/configs?accessToken=${TOKEN}" \
  --data-urlencode "dataId=${DATA_ID}" \
  --data-urlencode "group=${NACOS_GROUP}" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=${CONTENT}")"
[ "$HTTP_CODE" = "200" ] || { echo "[init-nacos] 推送失败 HTTP $HTTP_CODE" >&2; exit 1; }
echo "[init-nacos] 已推送 ${DATA_ID} (group=${NACOS_GROUP})，内容含 3 个密钥 + ems.device-name"
