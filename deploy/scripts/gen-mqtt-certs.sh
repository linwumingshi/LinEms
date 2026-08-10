#!/usr/bin/env bash
# =====================================================================
# 生成 MQTT TLS 证书（开发/演示专用；生产必须换受信 CA 签发）
#
# 输出（Git Bash 下运行）：
#   deploy/certs/server-cert.pem   服务端证书（SAN=DNS:localhost,IP:127.0.0.1）
#   deploy/certs/server-key.pem    服务端私钥（PKCS#8 PEM）
#   deploy/certs/ca-cert.pem       设备 CA 根证书（mTLS 用，-c 参数生成）
#   deploy/certs/device-{clientId}-cert.pem / device-{clientId}-key.pem  设备证书（-d 参数签发）
#
# 用法：
#   bash deploy/scripts/gen-mqtt-certs.sh               # 仅服务端证书（单向 TLS）
#   bash deploy/scripts/gen-mqtt-certs.sh -c            # 服务端证书 + 设备 CA（mTLS 用）
#   bash deploy/scripts/gen-mqtt-certs.sh -d <clientId> # 用 CA 签发一台设备证书（CN=clientId）
#
# 环境变量：TLS_CERT_DAYS 有效天数（默认 365）
#
# 说明：
#   - genpkey 输出 PKCS#8（BEGIN PRIVATE KEY），规避 Netty PemReader 对 PKCS#1 的解析歧义；
#   - Git Bash 会把 -subj "/CN=..." 的起始斜杠篡改成 Windows 路径，故 req 命令单发
#     MSYS2_ARG_CONV_EXCL='*' 关闭参数路径转换（Linux/WSL 下该变量无副作用）；
#   - mTLS 启用（BROKER_TLS_CLIENT_AUTH=true）时 broker 要求设备证书且 CN=clientId，
#     trust-cert-file 指向 ca-cert.pem；
#   - deploy/certs/ 已被仓库根 .gitignore 排除，私钥不进入版本库。
# =====================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERT_DIR="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")/deploy/certs"
mkdir -p "$CERT_DIR"

DAYS="${TLS_CERT_DAYS:-365}"
SERVER_CERT="${CERT_DIR}/server-cert.pem"
SERVER_KEY="${CERT_DIR}/server-key.pem"
CA_CERT="${CERT_DIR}/ca-cert.pem"
CA_KEY="${CERT_DIR}/ca-key.pem"

gen_server_cert() {
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SERVER_KEY"
  MSYS2_ARG_CONV_EXCL='*' openssl req -new -x509 -key "$SERVER_KEY" -out "$SERVER_CERT" -days "$DAYS" \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    -addext "basicConstraints=critical,CA:FALSE" \
    -addext "keyUsage=digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth"
  chmod 600 "$SERVER_KEY" 2>/dev/null || true
  echo "已生成服务端证书:"
  echo "  $SERVER_CERT"
  echo "  $SERVER_KEY"
}

gen_ca() {
  if [ -f "$CA_CERT" ] && [ -f "$CA_KEY" ]; then
    echo "[gen-mqtt-certs] 设备 CA 已存在，跳过生成（$CA_CERT）"
    return
  fi
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$CA_KEY"
  MSYS2_ARG_CONV_EXCL='*' openssl req -new -x509 -key "$CA_KEY" -out "$CA_CERT" -days "$((DAYS * 2))" \
    -subj "/CN=EnergyX-Device-CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
  chmod 600 "$CA_KEY" 2>/dev/null || true
  echo "已生建设备 CA:"
  echo "  $CA_CERT"
  echo "  $CA_KEY（私钥请离线保管）"
}

gen_device_cert() {
  local client_id="$1"
  if [ -z "$client_id" ]; then
    echo "用法: bash deploy/scripts/gen-mqtt-certs.sh -d <clientId>" >&2
    exit 1
  fi
  if [ ! -f "$CA_CERT" ] || [ ! -f "$CA_KEY" ]; then
    echo "[gen-mqtt-certs] 设备 CA 不存在，先执行: bash deploy/scripts/gen-mqtt-certs.sh -c" >&2
    exit 1
  fi
  local dev_key="${CERT_DIR}/device-${client_id}-key.pem"
  local dev_csr="${CERT_DIR}/device-${client_id}.csr"
  local dev_cert="${CERT_DIR}/device-${client_id}-cert.pem"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dev_key"
  MSYS2_ARG_CONV_EXCL='*' openssl req -new -key "$dev_key" -out "$dev_csr" -subj "/CN=${client_id}"
  MSYS2_ARG_CONV_EXCL='*' openssl x509 -req -in "$dev_csr" -CA "$CA_CERT" -CAkey "$CA_KEY" \
    -CAcreateserial -out "$dev_cert" -days "$DAYS" \
    -extfile <(printf "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=clientAuth")
  rm -f "$dev_csr"
  chmod 600 "$dev_key" 2>/dev/null || true
  echo "已签发设备证书（CN=${client_id}）:"
  echo "  $dev_cert"
  echo "  $dev_key"
  echo "SDK 连接参数: ca=$CA_CERT cert=$dev_cert key=$dev_key"
}

case "${1:-}" in
  -c) gen_server_cert; gen_ca ;;
  -d) gen_server_cert; gen_device_cert "${2:-}" ;;
  "") gen_server_cert ;;
  *) echo "用法: gen-mqtt-certs.sh [-c] [-d <clientId>]" >&2; exit 1 ;;
esac
