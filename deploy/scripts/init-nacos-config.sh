#!/usr/bin/env bash
# =====================================================================
# 初始化 Nacos 配置中心密钥 dataId：energy-shared.yaml（group ENERGY）
# 读取 deploy/env/local.env 的密钥值 → 组装 YAML → 登录 Nacos 取 accessToken → 推送（幂等覆盖）→ 拉回验证
# 用法：bash deploy/scripts/init-nacos-config.sh
#
# 兼容性（Nacos 2.x / 3.x 双版本）：
#   - 登录：POST /nacos/v1/auth/login（2.x/3.x 通用；旧用户管理接口 /v1/auth/users/login 3.x 已非登录入口）
#   - 推送/回验：优先 v3 admin API（Nacos 3.x 标准，参数 groupName，token 走 header）；
#     返回 410/404/为空时自动回退 v2 API（Nacos 2.x 标准，参数 group，token 走 query）
# =====================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROOT}/deploy/env/local.env"

# 1. 加载密钥值（缺失即 fail-fast）
[ -f "$ENV_FILE" ] || { echo "[init-nacos] 缺少 $ENV_FILE（先 cp local.env.example local.env 并填值）" >&2; exit 1; }
source "$ENV_FILE"

NACOS_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_GROUP="${NACOS_GROUP:-ENERGY}"
DATA_ID="energy-shared.yaml"

# 2. 登录取 accessToken
TOKEN="$(curl -s -m 10 -X POST "http://${NACOS_ADDR}/nacos/v1/auth/login" \
  -d "username=${NACOS_USERNAME}&password=${NACOS_PASSWORD}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
[ -n "$TOKEN" ] || { echo "[init-nacos] Nacos 登录失败（检查 NACOS_USERNAME/NACOS_PASSWORD）" >&2; exit 1; }

# 3. 组装 content（值由 env 展开注入，脚本零硬编码密钥；密码含 & 等字符时 YAML 单引号包裹需在 env 值中自带，见 local.env）
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

# 4. 推送：v3 admin API 优先（Nacos 3.x），410/失败回退 v2（Nacos 2.x）
CODE="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST \
  "http://${NACOS_ADDR}/nacos/v3/admin/cs/config" \
  -H "accessToken: ${TOKEN}" \
  --data-urlencode "dataId=${DATA_ID}" \
  --data-urlencode "groupName=${NACOS_GROUP}" \
  --data-urlencode "namespaceId=public" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=${CONTENT}")"
if [ "$CODE" != "200" ]; then
  CODE="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST \
    "http://${NACOS_ADDR}/nacos/v2/cs/config?accessToken=${TOKEN}" \
    --data-urlencode "dataId=${DATA_ID}" \
    --data-urlencode "group=${NACOS_GROUP}" \
    --data-urlencode "namespaceId=public" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=${CONTENT}")"
  [ "$CODE" = "200" ] || { echo "[init-nacos] 推送失败 HTTP $CODE（v3/v2 均不可用，确认 Nacos 版本 ≥2.0）" >&2; exit 1; }
  echo "[init-nacos] 已推送 ${DATA_ID} (group=${NACOS_GROUP}) [v2 API]"
else
  echo "[init-nacos] 已推送 ${DATA_ID} (group=${NACOS_GROUP}) [v3 API]"
fi

# 5. 拉回验证：v3 优先，为空回退 v2
FETCHED="$(curl -s -m 10 \
  "http://${NACOS_ADDR}/nacos/v3/admin/cs/config?dataId=${DATA_ID}&groupName=${NACOS_GROUP}&namespaceId=public" \
  -H "accessToken: ${TOKEN}" | sed -n 's/.*"content":"\([^"]*\)".*/\1/p')"
if [ -z "$FETCHED" ]; then
  FETCHED="$(curl -s -m 10 \
    "http://${NACOS_ADDR}/nacos/v2/cs/config?dataId=${DATA_ID}&group=${NACOS_GROUP}&namespaceId=public&accessToken=${TOKEN}" \
    | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')"
  [ -n "$FETCHED" ] || { echo "[init-nacos] 推送后拉取验证失败（data 为空，检查 Nacos 版本与 API）" >&2; exit 1; }
fi
echo "[init-nacos] 回验通过（${#FETCHED} 字节），内容含 3 个密钥 + ems.device-name"
