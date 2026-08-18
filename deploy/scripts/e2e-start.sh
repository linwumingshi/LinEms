#!/usr/bin/env bash
# 一次性联调启动脚本：energy-system/alarm/command/rule/gateway
# 统一注入：NACOS 凭据 + 数据库密码 + JWT secret（env 优先级高于 Nacos config data，绕过拉取空问题）
set -u
JAVA="D:\Program Files\Java\graalvm-jdk-22.0.1\bin\java.exe"
ROOT="D:\ProgramData\_GitHub\EnergyStorageIotPlatform"
LOG="$ROOT\deploy\logs"
export NACOS_USERNAME=nacos
export NACOS_PASSWORD=nacos
export SPRING_DATASOURCE_PASSWORD='root&QAQ'
export ENERGYX_JWT_SECRET='sanduo-ems-dev-secret-0123456789abcdefghijklmnop'
unset SERVER__PORT SERVER__HOST 2>/dev/null

SERVICES=(energy-system energy-alarm energy-command energy-rule energy-gateway)
for name in "${SERVICES[@]}"; do
  jar="$ROOT\\backend\\$name\\target\\$name-1.0.0-SNAPSHOT.jar"
  "$JAVA" -jar "$jar" > "$LOG\\$name-e2e2.log" 2>&1 &
  echo "STARTED $name pid=$!"
done
echo "ALL_LAUNCHED"
