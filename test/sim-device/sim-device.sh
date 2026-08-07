#!/usr/bin/env bash
# 三多平台交互式单设备模拟器启动脚本
# 依赖：target/sim-device.jar（构建：cd test/sim-device && mvn package）
# 用法：./sim-device.sh [--product pk] [--device dn] [--secret-base s | --secret hex] [--broker host:port] [--autoack]
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "$(dirname "$0")/target/sim-device.jar" "$@"
