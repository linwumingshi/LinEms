# sim-device 交互式单设备模拟器

见 [sim-device 设计文档](../../docs/superpowers/specs/2026-08-07-sim-device-design.md)。

构建（需先 `cd sdk/java && mvn install`）：
    cd test/sim-device && mvn package

运行：
    ./sim-device.sh [--product pk] [--device dn] [--secret-base s | --secret hex] [--broker host:port] [--autoack]

命令：connect/disconnect/reconnect、report [k=v...]、event、lifecycle、status、ack、autoack、help、quit。
设备密钥派生与 test/stress seed 一致：hex(SHA-256(secret-base:index))，index 取 deviceName 数字后缀。
