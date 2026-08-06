#!/usr/bin/env bash
# =====================================================================
# 生成 MQTT TLS 自签名证书（开发/演示专用；生产必须换受信 CA 签发）
#
# 输出（Git Bash 下运行）：
#   deploy/certs/server-cert.pem   自签名服务端证书（SAN=DNS:localhost,IP:127.0.0.1）
#   deploy/certs/server-key.pem    私钥（PKCS#8 PEM，chmod 600 尽力而为）
#
# 用法：bash deploy/scripts/gen-mqtt-certs.sh
# 环境变量：TLS_CERT_DAYS 有效天数（默认 365）
#
# 说明：
#   - genpkey 输出 PKCS#8（BEGIN PRIVATE KEY），规避 Netty PemReader 对 PKCS#1 的解析歧义；
#   - Git Bash 会把 -subj "/CN=..." 的起始斜杠篡改成 Windows 路径，故 req 命令单发
#     MSYS2_ARG_CONV_EXCL='*' 关闭参数路径转换（Linux/WSL 下该变量无副作用）；
#   - 证书路径经 BROKER_TLS_CERT / BROKER_TLS_KEY 注入 broker（见 energy-mqtt-broker/application.yml），
#     默认相对路径按启动进程 CWD 解析，生产请传绝对路径；
#   - deploy/certs/ 已被仓库根 .gitignore 排除，私钥不进入版本库。
# =====================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERT_DIR="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")/deploy/certs"
mkdir -p "$CERT_DIR"

DAYS="${TLS_CERT_DAYS:-365}"
CERT="${CERT_DIR}/server-cert.pem"
KEY="${CERT_DIR}/server-key.pem"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY"
MSYS2_ARG_CONV_EXCL='*' openssl req -new -x509 -key "$KEY" -out "$CERT" -days "$DAYS" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth"
chmod 600 "$KEY" 2>/dev/null || true

echo "已生成:"
echo "  $CERT"
echo "  $KEY"
echo "SAN 校验:"
openssl x509 -in "$CERT" -noout -text | grep -A1 "Subject Alternative Name"
