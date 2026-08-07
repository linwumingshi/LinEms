# 交互式单设备模拟器 sim-device 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 `test/sim-device` 独立 Maven 模块：交互式 CLI REPL 模拟单台设备接入三多平台（HMAC 接入 Broker、上报属性/事件/生命周期、手动/自动回 ACK 下行命令）。

**Architecture:** 复用 `sanduo-device-sdk-1.0.0`（HMAC 认证、订阅、发布全在 SDK）。四个主类按职责拆分：`CliArgs`（纯参数解析）、`PendingCommands`（线程安全待处理队列）、`Connector`（MqttDevice 生命周期薄封装 + 可运行时切换的自动 ACK）、`Repl`（stdin 命令循环，解析抽为静态方法可单测）。构建用 maven-shade fat jar + `sim-device.sh`，完全复刻 `test/stress` 模式。

**Tech Stack:** Java 17、Maven（shade 3.5.1）、sanduo-device-sdk 1.0.0、slf4j/logback、JUnit 5（test scope）。

## Global Constraints

- Java 17；`maven.compiler.source/target = 17`；编码 UTF-8。
- 运行期依赖仅：`sanduo-device-sdk:1.0.0` + slf4j-api + logback-classic（jackson 由 SDK 传递，不显式声明）。
- 测试期新增 `junit-jupiter:5.10.2`（test scope）+ `maven-surefire-plugin:3.2.5`。
- 构建方式：maven-shade-plugin 3.5.1，`finalName=sim-device`，mainClass=`com.sanduo.simdevice.SimDeviceCli`，`ServicesResourceTransformer`，剔除 META-INF 签名文件。
- 不写 MySQL、不连 Nacos、不实现 TLS、不做引号/转义解析、不写 .bat。
- 密钥派生公式与 `test/stress` 的 `Secrets.deriveSecret` 完全一致：`hex(SHA-256(secretBase + ":" + index))`，index 取 `--device` 数字后缀。
- 构建前必须先把 SDK install 到本地仓库：`cd sdk/java && mvn -q install -DskipTests`。
- **本地 maven 仓库是 `D:\Program Files\maven-repo`**（全局 settings.xml 的 `localRepository`，非默认 `~/.m2`）；验证 SDK 是否已 install 查 `D:/Program Files/maven-repo/com/sanduo/sanduo-device-sdk/1.0.0/`。
- 所有 REPL 输出与 IO 线程横幅打印共用一把 `printLock`，避免乱行。
- Git 提交信息用 `feat(test/sim-device): <简述>` 前缀。

---

### Task 1: 模块脚手架 + CliArgs 参数解析

**Files:**
- Create: `test/sim-device/pom.xml`
- Create: `test/sim-device/sim-device.sh`
- Create: `test/sim-device/src/main/java/com/sanduo/simdevice/DeviceSecret.java`
- Create: `test/sim-device/src/main/java/com/sanduo/simdevice/CliArgs.java`
- Test: `test/sim-device/src/test/java/com/sanduo/simdevice/CliArgsTest.java`

**Interfaces:**
- Produces（供 Task 3/5 使用）:
  - `CliArgs`（不可变）：`product()`、`deviceName()`、`deviceSecret()`、`host()`、`port()`、`autoAck()`，全部返回 String/int/boolean。
  - `CliArgs.parse(String[] args)` → `CliArgs`；非法输入抛 `IllegalArgumentException`。
  - `CliArgs.usage()` → String（帮助文本）。
  - `DeviceSecret.derive(String secretBase, int index)` → String（包可见，密钥派生）。

- [ ] **Step 1: 安装 SDK 到本地仓库**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/sdk/java"
mvn -q install -DskipTests
ls "D:/Program Files/maven-repo/com/sanduo/sanduo-device-sdk/1.0.0/sanduo-device-sdk-1.0.0.jar"
```
Expected: 出现 jar 文件（SDK 已 install，后续 `mvn test/package` 才能解析依赖）。
> 本机 maven 本地仓库被全局 settings.xml 指到 `D:\Program Files\maven-repo`（非默认 `~/.m2`），上一条 `ls` 就是查那里。

- [ ] **Step 2: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.sanduo</groupId>
    <artifactId>sanduo-sim-device</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>sanduo-sim-device</name>
    <description>三多平台交互式单设备模拟器：HMAC 接入 / 上报 / 手动 ACK 下行命令</description>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <sdk.version>1.0.0</sdk.version>
        <slf4j.version>2.0.13</slf4j.version>
        <logback.version>1.4.14</logback.version>
        <junit.version>5.10.2</junit.version>
        <surefire.version>3.2.5</surefire.version>
        <shade.version>3.5.1</shade.version>
        <compiler.version>3.13.0</compiler.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.sanduo</groupId>
            <artifactId>sanduo-device-sdk</artifactId>
            <version>${sdk.version}</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>${slf4j.version}</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>sim-device</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${compiler.version}</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${surefire.version}</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${shade.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.sanduo.simdevice.SimDeviceCli</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 写启动脚本 sim-device.sh（并在 Git Bash chmod +x）**

```bash
#!/usr/bin/env bash
# 三多平台交互式单设备模拟器启动脚本
# 依赖：target/sim-device.jar（构建：cd test/sim-device && mvn package）
# 用法：./sim-device.sh [--product pk] [--device dn] [--secret-base s | --secret hex] [--broker host:port] [--autoack]
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "$(dirname "$0")/target/sim-device.jar" "$@"
```
```bash
chmod +x "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device/sim-device.sh"
```

- [ ] **Step 4: 写失败测试 CliArgsTest**

```java
package com.sanduo.simdevice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest {

    @Test
    void defaults() {
        CliArgs a = CliArgs.parse(new String[]{});
        assertEquals("snd_ess_pcs", a.product());
        assertEquals("sim-dev-000001", a.deviceName());
        assertEquals("127.0.0.1", a.host());
        assertEquals(1883, a.port());
        assertEquals(false, a.autoAck());
        assertEquals(DeviceSecret.derive("sanduo-stress", 1), a.deviceSecret());
    }

    @Test
    void deriveFromSecretBase() {
        CliArgs a = CliArgs.parse(new String[]{"--device", "sim-dev-000007", "--secret-base", "foo"});
        assertEquals(DeviceSecret.derive("foo", 7), a.deviceSecret());
    }

    @Test
    void explicitSecretWins() {
        CliArgs a = CliArgs.parse(new String[]{"--secret", "aabbccddeeff00112233445566778899", "--secret-base", "other"});
        assertEquals("aabbccddeeff00112233445566778899", a.deviceSecret());
    }

    @Test
    void invalidDeviceNameWithUnderscoreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--device", "sim_dev_1"}));
        assertTrue(e.getMessage().contains("_"));
    }

    @Test
    void parseBroker() {
        CliArgs a = CliArgs.parse(new String[]{"--broker", "10.0.0.5:2883"});
        assertEquals("10.0.0.5", a.host());
        assertEquals(2883, a.port());
    }

    @Test
    void invalidBrokerRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--broker", "localhost"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--broker", "localhost:0"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--broker", "localhost:notaport"}));
    }

    @Test
    void unknownFlagRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--nope"}));
    }

    @Test
    void missingValueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--product"}));
    }

    @Test
    void autoackFlag() {
        CliArgs a = CliArgs.parse(new String[]{"--autoack"});
        assertTrue(a.autoAck());
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: 编译失败 `程序包 com.sanduo.simdevice 不存在`（CliArgs/DeviceSecret 尚未实现）。

- [ ] **Step 6: 实现 DeviceSecret.java**

```java
package com.sanduo.simdevice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 设备密钥确定性派生，与 test/stress 的 {@code Secrets.deriveSecret} 同一公式，
 * 保证同一批 seed 出的设备在模拟器中密钥可复现：
 * deviceSecret = hex(SHA-256(secretBase + ":" + index))。
 */
final class DeviceSecret {

    private DeviceSecret() {
    }

    static String derive(String secretBase, int index) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((secretBase + ":" + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

- [ ] **Step 7: 实现 CliArgs.java**

```java
package com.sanduo.simdevice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sim-device 命令行参数解析（纯函数，可单测）。
 *
 * <pre>
 * sim-device [--product snd_ess_pcs] [--device sim-dev-000001]
 *            [--secret-base sanduo-stress | --secret &lt;hex&gt;]
 *            [--broker 127.0.0.1:1883] [--autoack]
 * </pre>
 *
 * 密钥优先级：--secret 显式值 &gt; --secret-base 派生（默认 sanduo-stress）；
 * 派生公式与 test/stress Secrets.deriveSecret 一致（{@link DeviceSecret}）。
 */
public final class CliArgs {

    private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)$");

    private final String product;
    private final String deviceName;
    private final String deviceSecret;
    private final String host;
    private final int port;
    private final boolean autoAck;

    private CliArgs(String product, String deviceName, String deviceSecret,
                    String host, int port, boolean autoAck) {
        this.product = product;
        this.deviceName = deviceName;
        this.deviceSecret = deviceSecret;
        this.host = host;
        this.port = port;
        this.autoAck = autoAck;
    }

    public String product() {
        return product;
    }

    public String deviceName() {
        return deviceName;
    }

    public String deviceSecret() {
        return deviceSecret;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean autoAck() {
        return autoAck;
    }

    /** 解析命令行参数；非法输入抛 {@link IllegalArgumentException}。 */
    public static CliArgs parse(String[] args) {
        String product = "snd_ess_pcs";
        String deviceName = "sim-dev-000001";
        String secretBase = "sanduo-stress";
        String explicitSecret = null;
        String host = "127.0.0.1";
        int port = 1883;
        boolean autoAck = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--product" -> product = value(args, ++i, "--product");
                case "--device" -> deviceName = value(args, ++i, "--device");
                case "--secret-base" -> secretBase = value(args, ++i, "--secret-base");
                case "--secret" -> explicitSecret = value(args, ++i, "--secret");
                case "--broker" -> {
                    String[] hp = parseBroker(value(args, ++i, "--broker"));
                    host = hp[0];
                    port = Integer.parseInt(hp[1]);
                }
                case "--autoack" -> autoAck = true;
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }

        validateDeviceName(deviceName);
        String secret = explicitSecret != null
                ? explicitSecret
                : DeviceSecret.derive(secretBase, indexFromDeviceName(deviceName));
        return new CliArgs(product, deviceName, secret, host, port, autoAck);
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("缺少 " + flag + " 的参数值");
        }
        return args[i];
    }

    /** host:port 拆分；端口必须为 1-65535。 */
    static String[] parseBroker(String broker) {
        String[] hp = broker.split(":", 2);
        if (hp.length != 2 || hp[0].isBlank()) {
            throw new IllegalArgumentException("broker 格式应为 host:port（当前: " + broker + "）");
        }
        int port;
        try {
            port = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("broker 端口非法: " + hp[1]);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("broker 端口越界: " + port);
        }
        return hp;
    }

    /** deviceName 禁 '_'/'&'（与 Broker 按最后一个 '_' 拆 clientId、username 分隔符冲突）。 */
    static void validateDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            throw new IllegalArgumentException("--device 不能为空");
        }
        if (deviceName.contains("_") || deviceName.contains("&")) {
            throw new IllegalArgumentException(
                    "deviceName 不允许包含 '_' 或 '&'（当前: " + deviceName + "）");
        }
    }

    /** 从 deviceName 数字后缀解析 index（sim-dev-000001 → 1）。 */
    static int indexFromDeviceName(String deviceName) {
        Matcher m = TRAILING_DIGITS.matcher(deviceName);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "使用 --secret-base 派生密钥需要 --device 以数字结尾（当前: " + deviceName + "）");
        }
        return Integer.parseInt(m.group(1));
    }

    public static String usage() {
        return """
                用法: sim-device [选项]
                  --product <pk>       产品标识（默认 snd_ess_pcs）
                  --device <dn>        设备名（默认 sim-dev-000001，须已注册）
                  --secret-base <s>    密钥派生基串（默认 sanduo-stress）；
                                       密钥 = hex(SHA-256(<s>:<index>))，index 取 --device 数字后缀
                  --secret <hex>       显式设备密钥（优先于 --secret-base 派生）
                  --broker <host:port> Broker 地址（默认 127.0.0.1:1883）
                  --autoack            启动即自动回 ACK
                  --help               显示本帮助""";
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: BUILD SUCCESS，CliArgsTest 9 个用例全绿。

- [ ] **Step 9: 验证 fat jar 可构建**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q package -DskipTests && ls -la target/sim-device.jar`
Expected: 生成 `target/sim-device.jar`（shade 产物，主类 Manifest 指向暂不存在的 SimDeviceCli，属预期）。

- [ ] **Step 10: Commit**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform"
git add test/sim-device/pom.xml test/sim-device/sim-device.sh \
        test/sim-device/src/main/java/com/sanduo/simdevice/DeviceSecret.java \
        test/sim-device/src/main/java/com/sanduo/simdevice/CliArgs.java \
        test/sim-device/src/test/java/com/sanduo/simdevice/CliArgsTest.java
git commit -m "feat(test/sim-device): 模块脚手架 + CLI 参数解析（密钥派生/优先级/broker 校验）"
```

---

### Task 2: PendingCommands 待处理命令队列

**Files:**
- Create: `test/sim-device/src/main/java/com/sanduo/simdevice/PendingCommands.java`
- Test: `test/sim-device/src/test/java/com/sanduo/simdevice/PendingCommandsTest.java`

**Interfaces:**
- Consumes: `com.sanduo.device.CommandMessage`（SDK，链式 `setCommandId/setCommand/setParams`，含 `commandId()`）。
- Produces（供 Task 3/4 使用）:
  - `PendingCommands.add(CommandMessage)`、`latest()`（队尾最新，空返回 null）、`remove(String commandId)`（命中返回并移除，否则 null）、`pendingCount()`、`isEmpty()`。

- [ ] **Step 1: 写失败测试 PendingCommandsTest**

```java
package com.sanduo.simdevice;

import com.sanduo.device.CommandMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingCommandsTest {

    private static CommandMessage cmd(String id) {
        return new CommandMessage().setCommandId(id).setCommand("exec").setParams(java.util.Map.of());
    }

    @Test
    void emptyInitially() {
        PendingCommands p = new PendingCommands();
        assertTrue(p.isEmpty());
        assertEquals(0, p.pendingCount());
        assertNull(p.latest());
    }

    @Test
    void latestReturnsMostRecent() {
        PendingCommands p = new PendingCommands();
        p.add(cmd("c1"));
        p.add(cmd("c2"));
        p.add(cmd("c3"));
        assertEquals("c3", p.latest().commandId());
        assertEquals(3, p.pendingCount());
    }

    @Test
    void removeById() {
        PendingCommands p = new PendingCommands();
        p.add(cmd("c1"));
        p.add(cmd("c2"));
        CommandMessage removed = p.remove("c2");
        assertEquals("c2", removed.commandId());
        assertEquals(1, p.pendingCount());
        assertNull(p.remove("nope"));
        assertEquals(1, p.pendingCount());
    }

    @Test
    void concurrentAddIsSafe() throws InterruptedException {
        PendingCommands p = new PendingCommands();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        p.add(cmd("t" + tid + "-" + i));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(threads * perThread, p.pendingCount());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: 编译失败 `找不到符号: 类 PendingCommands`。

- [ ] **Step 3: 实现 PendingCommands.java**

```java
package com.sanduo.simdevice;

import com.sanduo.device.CommandMessage;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 下行命令待处理队列（线程安全）。
 *
 * <p>SDK 的 onCommand 回调在 Netty IO 线程触发 → {@link #add}；
 * REPL 线程的 ack/status 通过 {@link #latest} / {@link #remove} 取用。</p>
 */
public final class PendingCommands {

    private final ConcurrentLinkedQueue<CommandMessage> queue = new ConcurrentLinkedQueue<>();

    public void add(CommandMessage command) {
        queue.add(command);
    }

    /** 最新一条待处理命令（队尾），队列空返回 null。 */
    public CommandMessage latest() {
        CommandMessage last = null;
        for (CommandMessage c : queue) {
            last = c;
        }
        return last;
    }

    /** 按 commandId 移除并返回；未找到返回 null。 */
    public CommandMessage remove(String commandId) {
        if (commandId == null) {
            return null;
        }
        for (Iterator<CommandMessage> it = queue.iterator(); it.hasNext(); ) {
            CommandMessage c = it.next();
            if (commandId.equals(c.commandId())) {
                it.remove();
                return c;
            }
        }
        return null;
    }

    public int pendingCount() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: BUILD SUCCESS，PendingCommandsTest 4 个用例全绿。

- [ ] **Step 5: Commit**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform"
git add test/sim-device/src/main/java/com/sanduo/simdevice/PendingCommands.java \
        test/sim-device/src/test/java/com/sanduo/simdevice/PendingCommandsTest.java
git commit -m "feat(test/sim-device): 下行命令待处理队列（线程安全，取最新/按 id 移除）"
```

---

### Task 3: Connector MqttDevice 生命周期封装

**Files:**
- Create: `test/sim-device/src/main/java/com/sanduo/simdevice/Connector.java`
- Test: `test/sim-device/src/test/java/com/sanduo/simdevice/ConnectorTest.java`

**Interfaces:**
- Consumes: `DeviceIdentity`（SDK record，构造抛 IllegalArgumentException）、`MqttDevice`/`MqttClientConfig`/`DeviceListener`/`CommandMessage`（SDK）、`PendingCommands`（Task 2）。
- Produces（供 Task 4/5 使用）:
  - `Connector(DeviceIdentity identity, String host, int port, PendingCommands pending)`
  - `String connect()` / `String disconnect()` / `String reconnect()` / `String status()`
  - `boolean isConnected()`、`String clientId()`、`String broker()`
  - `boolean autoAck()`、`void setAutoAck(boolean)`
  - `void publishProperty(Map<String,Object>)` / `void publishEvent(String,int,String,Map)` / `void publishLifecycle(String,String)` / `void ackCommand(String,String)` — 未连接抛 `IllegalStateException`
  - `void setOnCommandArrived(Consumer<CommandMessage>)`、`void setOnError(Consumer<Throwable>)`
  - 包可见静态：`String connackHint(int)`、`String describeConnectError(IllegalStateException)`

- [ ] **Step 1: 写失败测试 ConnectorTest**

```java
package com.sanduo.simdevice;

import com.sanduo.device.DeviceIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorTest {

    private static final DeviceIdentity ID =
            new DeviceIdentity("snd_ess_pcs", "sim-dev-000001", DeviceSecret.derive("sanduo-stress", 1));

    @Test
    void connackHintCoversCommonCodes() {
        assertTrue(Connector.connackHint(4).contains("密码"));
        assertTrue(Connector.connackHint(5).contains("未授权"));
        assertTrue(Connector.connackHint(2).contains("clientId"));
    }

    @Test
    void disconnectWhenNotConnected() {
        Connector c = new Connector(ID, "127.0.0.1", 1883, new PendingCommands());
        assertEquals("未连接", c.disconnect());
        assertEquals(false, c.isConnected());
    }

    @Test
    void statusBeforeConnect() {
        Connector c = new Connector(ID, "127.0.0.1", 1883, new PendingCommands());
        String s = c.status();
        assertTrue(s.contains("未连接"));
        assertTrue(s.contains("sim-dev-000001"));
        assertTrue(s.contains("127.0.0.1:1883"));
    }

    @Test
    void describeConnectErrorMapsRejection() {
        IllegalStateException e = new IllegalStateException(
                "连接被拒绝 code=4 clientId=snd_ess_pcs_sim-dev-000001");
        String s = Connector.describeConnectError(e);
        assertTrue(s.contains("连接被拒绝"));
        assertTrue(s.contains("密码"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: 编译失败 `找不到符号: 类 Connector`。

- [ ] **Step 3: 实现 Connector.java**

```java
package com.sanduo.simdevice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sanduo.device.CommandMessage;
import com.sanduo.device.DeviceIdentity;
import com.sanduo.device.DeviceListener;
import com.sanduo.device.MqttClientConfig;
import com.sanduo.device.MqttDevice;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 封装 MqttDevice 生命周期与薄封装：connect/disconnect/reconnect、上报、
 * 手动/自动 ACK。仅依赖 SDK，不涉及 REPL 的 IO。
 *
 * <p>自动回 ACK 由本类自行实现（不依赖 config.autoAck），以便运行时切换：
 * SDK 收到指令后回调 listener，若 {@code autoAck} 为真则立即回 SUCCESS。</p>
 */
public final class Connector {

    private final DeviceIdentity identity;
    private final String host;
    private final int port;
    private final PendingCommands pending;
    private final MqttClientConfig config;
    private final AtomicBoolean autoAck = new AtomicBoolean(false);
    private volatile MqttDevice device;
    private volatile Consumer<CommandMessage> onCommandArrived;
    private volatile Consumer<Throwable> onError;

    public Connector(DeviceIdentity identity, String host, int port, PendingCommands pending) {
        this.identity = identity;
        this.host = host;
        this.port = port;
        this.pending = pending;
        this.config = MqttClientConfig.defaults()
                .host(host).port(port)
                .connectTimeoutMs(10_000)
                .keepAliveSeconds(60)
                .subscribeCommand(true)
                .autoAck(false)        // 手动 ACK 由本类 listener 控制（支持运行时切换）
                .autoReconnect(false); // 交互式工具：断开不静默重连，由用户显式 reconnect
    }

    /** REPL 注册：收到下行命令时回调（用于中断打印）。 */
    public void setOnCommandArrived(Consumer<CommandMessage> callback) {
        this.onCommandArrived = callback;
    }

    /** REPL 注册：SDK 异常（下行解析失败/通道异常）回调。 */
    public void setOnError(Consumer<Throwable> callback) {
        this.onError = callback;
    }

    /** 建立（或重建）连接。返回展示用结果行；失败返回含原因/建议的提示行。 */
    public String connect() {
        if (isConnected()) {
            return "已连接 " + identity.clientId() + " @ " + host + ":" + port;
        }
        MqttDevice d = new MqttDevice(identity, config, listener);
        device = d;
        try {
            d.connect();
            return "已连接 " + identity.clientId() + " @ " + host + ":" + port;
        } catch (IllegalStateException e) {
            device = null;
            return describeConnectError(e);
        }
    }

    public String disconnect() {
        MqttDevice d = device;
        if (d == null || !d.isConnected()) {
            device = null;
            return "未连接";
        }
        try {
            d.close();
        } catch (Exception ignore) {
            // 尽力优雅断开，失败不阻塞
        }
        device = null;
        return "已断开 " + identity.clientId();
    }

    public String reconnect() {
        disconnect();
        return connect();
    }

    public String status() {
        return "连接: " + (isConnected() ? "已连接" : "未连接") + "\n"
                + "clientId: " + identity.clientId() + "\n"
                + "broker: " + host + ":" + port + "\n"
                + "待处理命令: " + pending.pendingCount() + "\n"
                + "自动回 ACK: " + (autoAck.get() ? "on" : "off");
    }

    public boolean isConnected() {
        MqttDevice d = device;
        return d != null && d.isConnected();
    }

    public String clientId() {
        return identity.clientId();
    }

    public String broker() {
        return host + ":" + port;
    }

    public boolean autoAck() {
        return autoAck.get();
    }

    public void setAutoAck(boolean on) {
        autoAck.set(on);
    }

    public void publishProperty(Map<String, Object> props) {
        requireConnected();
        try {
            device.publishProperty(props);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("属性序列化失败", e);
        }
    }

    public void publishEvent(String name, int severity, String code, Map<String, Object> data) {
        requireConnected();
        try {
            device.publishEvent(name, severity, code, data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
    }

    public void publishLifecycle(String eventType, String ip) {
        requireConnected();
        try {
            device.publishLifecycle(eventType, ip);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("生命周期序列化失败", e);
        }
    }

    public void ackCommand(String commandId, String status) {
        requireConnected();
        device.ackCommand(commandId, status, null,
                status.equals("SUCCESS") ? Map.of("exec", "ok") : null);
    }

    private void requireConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("MQTT 未连接，先 connect");
        }
    }

    // ------------------------------------------------------------------
    // 回调（Netty IO 线程触发，禁止阻塞）
    // ------------------------------------------------------------------

    private final DeviceListener listener = new DeviceListener() {
        @Override
        public void onConnected(DeviceIdentity identity) {
        }

        @Override
        public void onCommand(DeviceIdentity identity, CommandMessage command) {
            pending.add(command);
            if (autoAck.get()) {
                MqttDevice d = device;
                if (d != null) {
                    d.ackCommand(command);
                }
            }
            Consumer<CommandMessage> cb = onCommandArrived;
            if (cb != null) {
                cb.accept(command);
            }
        }

        @Override
        public void onDisconnected(DeviceIdentity identity, String reason) {
        }

        @Override
        public void onError(DeviceIdentity identity, Throwable cause) {
            Consumer<Throwable> cb = onError;
            if (cb != null) {
                cb.accept(cause);
            }
        }
    };

    // ------------------------------------------------------------------
    // 连接错误 → 中文提示
    // ------------------------------------------------------------------

    /** CONNACK 返回码 → 中文说明。 */
    static String connackHint(int code) {
        return switch (code) {
            case 1 -> "协议版本不支持（MQTT 3.1.1）";
            case 2 -> "clientId 非法";
            case 4 -> "密码错误或设备未注册/未激活（校验 --secret/--device 是否与 seed 一致）";
            case 5 -> "未授权";
            default -> "连接被拒绝 code=" + code;
        };
    }

    static String describeConnectError(IllegalStateException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("连接被拒绝 code=")) {
            int idx = msg.indexOf("code=") + "code=".length();
            int end = idx;
            while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
                end++;
            }
            int code = Integer.parseInt(msg.substring(idx, end));
            return "连接被拒绝: " + connackHint(code);
        }
        if (msg.contains("CONNACK 超时") || msg.contains("TCP 连接失败")) {
            return "连接失败: " + msg + "（检查 broker 是否在跑，或 --broker 地址）";
        }
        return "连接失败: " + msg;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: BUILD SUCCESS，ConnectorTest 4 个用例全绿（不依赖真实 broker）。

- [ ] **Step 5: Commit**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform"
git add test/sim-device/src/main/java/com/sanduo/simdevice/Connector.java \
        test/sim-device/src/test/java/com/sanduo/simdevice/ConnectorTest.java
git commit -m "feat(test/sim-device): Connector 封装 MqttDevice 生命周期 + 可切换自动 ACK"
```

---

### Task 4: Repl 交互式命令循环

**Files:**
- Create: `test/sim-device/src/main/java/com/sanduo/simdevice/Repl.java`
- Test: `test/sim-device/src/test/java/com/sanduo/simdevice/ReplParseTest.java`

**Interfaces:**
- Consumes: `Connector`（Task 3 全部方法）、`PendingCommands`（Task 2）、`com.sanduo.device.CommandMessage`。
- Produces（供 Task 5 使用）:
  - `Repl(Connector connector, PendingCommands pending, InputStream in, PrintStream out)`
  - `void run()`
  - 包可见：`void notifyCommand(CommandMessage)`、`void notifyError(Throwable)`（供 main 注册为 Connector 回调）
  - 包可见静态：`ParsedCommand parse(String line)`、record `Repl.ParsedCommand(String verb, List<String> args, String message)`
  - `static String usage()`

- [ ] **Step 1: 写失败测试 ReplParseTest**

```java
package com.sanduo.simdevice;

import com.sanduo.simdevice.Repl.ParsedCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplParseTest {

    @Test
    void reportParsesKV() {
        ParsedCommand c = Repl.parse("report soc=52 voltage=215");
        assertEquals("report", c.verb());
        assertEquals(List.of("soc=52", "voltage=215"), c.args());
        assertNull(c.message());
    }

    @Test
    void reportNoArgsOk() {
        ParsedCommand c = Repl.parse("report");
        assertEquals("report", c.verb());
        assertTrue(c.args().isEmpty());
    }

    @Test
    void ackDefaults() {
        assertEquals("ack", Repl.parse("ack").verb());
        assertEquals(List.of("abc"), Repl.parse("ack abc").args());
        assertEquals(List.of("abc", "FAILED"), Repl.parse("ack abc FAILED").args());
    }

    @Test
    void ackTooManyArgsRejected() {
        ParsedCommand c = Repl.parse("ack a b c");
        assertTrue(c.message() != null && c.message().contains("用法"));
    }

    @Test
    void autoackParses() {
        ParsedCommand on = Repl.parse("autoack on");
        assertEquals("autoack", on.verb());
        assertEquals(List.of("on"), on.args());
        assertEquals(List.of("off"), Repl.parse("autoack off").args());
    }

    @Test
    void autoackInvalidRejected() {
        assertTrue(Repl.parse("autoack maybe").message().contains("用法"));
        assertTrue(Repl.parse("autoack on extra").message().contains("用法"));
    }

    @Test
    void caseInsensitiveVerb() {
        assertEquals("report", Repl.parse("REPORT SOC=1").verb());
    }

    @Test
    void unknownCommandSuggestsHelp() {
        ParsedCommand c = Repl.parse("foo");
        assertTrue(c.message().contains("help"));
    }

    @Test
    void blankLineIsNoop() {
        ParsedCommand c = Repl.parse("   ");
        assertEquals("", c.verb());
        assertNull(c.message());
    }

    @Test
    void lifecycleRequiresState() {
        assertTrue(Repl.parse("lifecycle").message().contains("用法"));
        assertEquals("lifecycle", Repl.parse("lifecycle online 192.168.1.5").verb());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: 编译失败 `找不到符号: 类 Repl`。

- [ ] **Step 3: 实现 Repl.java**

```java
package com.sanduo.simdevice;

import com.sanduo.device.CommandMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 交互式命令循环：读 stdin → 解析 → 调用 Connector / PendingCommands → 打印。
 * 不直接触碰 MQTT。解析逻辑抽到 {@link #parse}（可单测）。
 */
public final class Repl {

    /** 固定属性字段集（与 test/stress ThroughputLoad 一致）。 */
    private static final String[] FIELDS = {"soc", "voltage", "current", "power", "temp", "runMode"};

    private final Connector connector;
    private final PendingCommands pending;
    private final BufferedReader in;
    private final PrintStream out;
    private final Random random = new Random();
    private final Object printLock = new Object();

    public Repl(Connector connector, PendingCommands pending, InputStream in, PrintStream out) {
        this.connector = connector;
        this.pending = pending;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    public void run() {
        while (true) {
            String line = readLine();
            if (line == null) {
                break; // EOF
            }
            ParsedCommand cmd = parse(line);
            if (cmd.message() != null) {
                println(cmd.message());
                continue;
            }
            switch (cmd.verb()) {
                case "connect" -> println(connector.connect());
                case "disconnect" -> println(connector.disconnect());
                case "reconnect" -> println(connector.reconnect());
                case "status" -> println(connector.status());
                case "report" -> execReport(cmd.args());
                case "event" -> execEvent(cmd.args());
                case "lifecycle" -> execLifecycle(cmd.args());
                case "ack" -> execAck(cmd.args());
                case "autoack" -> {
                    connector.setAutoAck(cmd.args().get(0).equals("on"));
                    println("自动回 ACK: " + cmd.args().get(0));
                }
                case "help" -> println(usage());
                case "quit" -> {
                    connector.disconnect();
                    return;
                }
                default -> println("未知命令（help 查看命令表）");
            }
        }
    }

    private void execReport(List<String> args) {
        Map<String, Object> props = args.isEmpty() ? randomProps() : parseKV(args, "属性");
        if (props == null) {
            return; // parseKV 已打印错误
        }
        try {
            connector.publishProperty(props);
            println("已上报属性: " + props);
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    private void execEvent(List<String> args) {
        String name = args.get(0);
        int severity = 1;
        String code = null;
        List<String> rest = new ArrayList<>(args.subList(1, args.size()));
        if (!rest.isEmpty() && isInteger(rest.get(0))) {
            severity = Integer.parseInt(rest.remove(0));
        }
        if (!rest.isEmpty() && !rest.get(0).contains("=")) {
            code = rest.remove(0);
        }
        Map<String, Object> data = parseKV(rest, "事件");
        if (data == null) {
            return;
        }
        try {
            connector.publishEvent(name, severity, code, data);
            println("已上报事件 " + name + " severity=" + severity
                    + (code != null ? " code=" + code : "") + " data=" + data);
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    private void execLifecycle(List<String> args) {
        String eventType = args.get(0);
        String ip = args.size() >= 2 ? args.get(1) : null;
        try {
            connector.publishLifecycle(eventType, ip);
            println("已上报上下线 " + eventType + (ip != null ? " ip=" + ip : ""));
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    private void execAck(List<String> args) {
        String status = args.size() >= 2 ? args.get(1).toUpperCase(Locale.ROOT) : "SUCCESS";
        if (!status.equals("SUCCESS") && !status.equals("FAILED")) {
            println("status 只能是 SUCCESS 或 FAILED");
            return;
        }
        String commandId = args.isEmpty() ? null : args.get(0);
        if (commandId == null) {
            CommandMessage latest = pending.latest();
            commandId = latest == null ? null : latest.commandId();
        }
        if (commandId == null) {
            println("没有待处理的命令（status 查看，或收到命令后 ack）");
            return;
        }
        try {
            connector.ackCommand(commandId, status);
            pending.remove(commandId);
            println("已回 ACK " + commandId + " → " + status);
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 参数辅助
    // ------------------------------------------------------------------

    /** 随机一组 6 字段属性（与 stress ThroughputLoad 分布一致）。 */
    private Map<String, Object> randomProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("soc", 40 + random.nextInt(60));
        props.put("voltage", 200 + random.nextInt(50));
        props.put("current", random.nextInt(40));
        props.put("power", 500 + random.nextInt(3000));
        props.put("temp", 25 + random.nextInt(20));
        props.put("runMode", random.nextInt(3));
        return props;
    }

    /** 解析 k=v 列表；任一参数缺 '=' 打印用法并返回 null。数值转 Long/Double，其余留字符串。 */
    private Map<String, Object> parseKV(List<String> tokens, String kind) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq <= 0) {
                println("参数需为 k=v 形式（" + kind + "）: " + t);
                return null;
            }
            map.put(t.substring(0, eq), parseValue(t.substring(eq + 1)));
        }
        return map;
    }

    /** 数值字符串转 Long/Double，否则原样字符串。 */
    private Object parseValue(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignore) {
            // fall through
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignore) {
            // fall through
        }
        return v;
    }

    private static boolean isInteger(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // IO（锁保护，避免与 IO 线程的下行命令横幅乱行）
    // ------------------------------------------------------------------

    private String readLine() {
        printPrompt();
        try {
            return in.readLine();
        } catch (IOException e) {
            println("读取输入失败: " + e.getMessage());
            return null;
        }
    }

    private void printPrompt() {
        synchronized (printLock) {
            out.print("sim-dev> ");
            out.flush();
        }
    }

    private void println(String s) {
        synchronized (printLock) {
            out.println(s);
        }
    }

    /** IO 线程回调：打印下行命令横幅并重绘 prompt。 */
    void notifyCommand(CommandMessage command) {
        synchronized (printLock) {
            out.print("\r");
            out.println("↓ 收到下行命令: " + command);
            if (connector.autoAck()) {
                out.println("  （autoack on，已自动回 SUCCESS）");
            }
            out.print("sim-dev> ");
            out.flush();
        }
    }

    /** IO 线程回调：SDK 异常横幅。 */
    void notifyError(Throwable cause) {
        synchronized (printLock) {
            out.print("\r");
            out.println("[SDK 异常] " + (cause.getMessage() == null ? cause : cause.getMessage()));
            out.print("sim-dev> ");
            out.flush();
        }
    }

    // ------------------------------------------------------------------
    // 解析（包可见，单测目标）
    // ------------------------------------------------------------------

    /** 解析一行命令为 ParsedCommand。空行→noop；未知/非法→带 message 的错误命令。 */
    static ParsedCommand parse(String line) {
        if (line == null) {
            return ParsedCommand.quit();
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.noop();
        }
        String[] parts = trimmed.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));
        switch (verb) {
            case "connect", "disconnect", "reconnect", "status" -> {
                if (!rest.isEmpty()) {
                    return ParsedCommand.error("用法: " + verb);
                }
                return ParsedCommand.of(verb);
            }
            case "report" -> {
                return ParsedCommand.of("report", rest);
            }
            case "event" -> {
                if (rest.isEmpty()) {
                    return ParsedCommand.error("用法: event <name> [severity] [code] [k=v...]");
                }
                return ParsedCommand.of("event", rest);
            }
            case "lifecycle" -> {
                if (rest.isEmpty()
                        || (!rest.get(0).equals("online") && !rest.get(0).equals("offline"))) {
                    return ParsedCommand.error("用法: lifecycle online|offline [ip]");
                }
                return ParsedCommand.of("lifecycle", rest);
            }
            case "ack" -> {
                if (rest.size() > 2) {
                    return ParsedCommand.error("用法: ack [commandId] [SUCCESS|FAILED]");
                }
                return ParsedCommand.of("ack", rest);
            }
            case "autoack" -> {
                if (rest.size() != 1
                        || (!rest.get(0).equals("on") && !rest.get(0).equals("off"))) {
                    return ParsedCommand.error("用法: autoack on|off");
                }
                return ParsedCommand.of("autoack", rest);
            }
            case "help" -> {
                return ParsedCommand.help();
            }
            case "quit", "exit" -> {
                return ParsedCommand.quit();
            }
            default -> {
                return ParsedCommand.unknown("未知命令: " + verb + "（help 查看命令表）");
            }
        }
    }

    /** 命令表。 */
    public static String usage() {
        return """
                命令:
                  connect / disconnect / reconnect   连接管理
                  report [k=v ...]                   上报属性（无参数 = 随机一组）
                  event <name> [severity] [code] [k=v...]   上报事件
                  lifecycle online|offline [ip]       上报上下线
                  status                              查看连接状态与待处理命令
                  ack [commandId] [SUCCESS|FAILED]    回指令 ACK（缺省取最新一条 / SUCCESS）
                  autoack on|off                      切换自动回 ACK
                  help                                显示本命令表
                  quit                                断开并退出""";
    }

    /** 解析结果：message 非空表示错误/未知命令（verb 为空）；verb="help"/"quit" 供执行分支识别。 */
    record ParsedCommand(String verb, List<String> args, String message) {

        static ParsedCommand of(String verb) {
            return new ParsedCommand(verb, List.of(), null);
        }

        static ParsedCommand of(String verb, List<String> args) {
            return new ParsedCommand(verb, List.copyOf(args), null);
        }

        static ParsedCommand noop() {
            return new ParsedCommand("", List.of(), null);
        }

        static ParsedCommand help() {
            return new ParsedCommand("help", List.of(), null);
        }

        static ParsedCommand quit() {
            return new ParsedCommand("quit", List.of(), null);
        }

        static ParsedCommand error(String message) {
            return new ParsedCommand("", List.of(), message);
        }

        static ParsedCommand unknown(String message) {
            return new ParsedCommand("", List.of(), message);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test`
Expected: BUILD SUCCESS，ReplParseTest 10 个用例全绿（其余任务用例也仍绿）。

- [ ] **Step 5: Commit**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform"
git add test/sim-device/src/main/java/com/sanduo/simdevice/Repl.java \
        test/sim-device/src/test/java/com/sanduo/simdevice/ReplParseTest.java
git commit -m "feat(test/sim-device): 交互式 REPL 命令循环（解析可单测，下行命令中断打印）"
```

---

### Task 5: SimDeviceCli 入口 + 端到端冒烟

**Files:**
- Create: `test/sim-device/src/main/java/com/sanduo/simdevice/SimDeviceCli.java`

**Interfaces:**
- Consumes: `CliArgs`（Task 1）、`PendingCommands`（Task 2）、`Connector`（Task 3）、`Repl`（Task 4）、`DeviceIdentity`（SDK）。

- [ ] **Step 1: 实现 SimDeviceCli.java**

```java
package com.sanduo.simdevice;

import com.sanduo.device.DeviceIdentity;

import java.util.Arrays;

/**
 * sim-device 入口：解析 CLI 参数 → 构造 DeviceIdentity/Connector/Repl →
 * 自动连接 → 进入交互式 REPL。
 */
public final class SimDeviceCli {

    private SimDeviceCli() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.println(CliArgs.usage());
            return;
        }
        CliArgs cli;
        try {
            cli = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println();
            System.err.println(CliArgs.usage());
            System.exit(2);
            return;
        }

        DeviceIdentity identity = new DeviceIdentity(cli.product(), cli.deviceName(), cli.deviceSecret());
        PendingCommands pending = new PendingCommands();
        Connector connector = new Connector(identity, cli.host(), cli.port(), pending);
        Repl repl = new Repl(connector, pending, System.in, System.out);
        connector.setOnCommandArrived(repl::notifyCommand);
        connector.setOnError(repl::notifyError);
        connector.setAutoAck(cli.autoAck());

        System.out.println("三多平台交互式模拟器");
        System.out.println("  clientId: " + identity.clientId());
        System.out.println("  broker:   " + cli.host() + ":" + cli.port());
        System.out.println("  autoack:  " + (cli.autoAck() ? "on" : "off"));
        System.out.println("输入 help 查看命令表");
        System.out.println(connector.connect());

        repl.run();
        System.out.println("已退出");
    }
}
```

- [ ] **Step 2: 全量测试 + 打 fat jar**

Run: `cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device" && mvn -q test && mvn -q package -DskipTests && ls -la target/sim-device.jar`
Expected: 测试全绿，`target/sim-device.jar` 生成，`java -jar` 可执行。

- [ ] **Step 3: 无 broker 下验证帮助/参数错误路径**

Run:
```bash
java -jar "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device/target/sim-device.jar" --help
java -jar "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device/target/sim-device.jar" --device sim_dev_bad  2>&1 | head -3
```
Expected: 第一行打印 usage；第二行打印「参数错误: deviceName 不允许包含 '_' 或 '&'」后退出码 2。

- [ ] **Step 4: Commit**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform"
git add test/sim-device/src/main/java/com/sanduo/simdevice/SimDeviceCli.java
git commit -m "feat(test/sim-device): sim-device 入口（--help/参数错误路径 + 自动连接进 REPL）"
```

- [ ] **Step 5: 手动冒烟——连接 + 上报属性落库**

前置：全栈已启动。先确认 broker：
```bash
powershell -Command "(Get-NetTCPConnection -LocalPort 1883 -State Listen | Measure-Object).Count"
```
若为 0，先起全栈：`cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform" && bash test/drill/start-stack.sh`（或按仓库既有方式）。

TDengine 行数基线：
```bash
curl -s -u root:taosdata -d "SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs" http://127.0.0.1:6041/rest/sql
```

运行模拟器（默认设备 sim-dev-000001，密钥由 sanduo-stress 派生；该设备已于 P0-5 基线 seed）：
```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/sim-device"
java -jar target/sim-device.jar
```
在 REPL 内依次输入：
```
status
report soc=52 voltage=215
report
event fault 2 ERR001 temp=88
lifecycle online 192.168.1.100
autoack off
```
Expected: 首行 `已连接 snd_ess_pcs_sim-dev-000001 @ 127.0.0.1:1883`；每次上报打印 `已上报属性: {soc=52, ...}`。`status` 显示已连接、待处理命令数。

再查一次 TDengine 行数，确认较基线增长（上报落库）：
```bash
curl -s -u root:taosdata -d "SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs" http://127.0.0.1:6041/rest/sql
```

> 若连接报「密码错误或设备未注册」（code=4），说明设备未 seed，先补 seed 再重试：
> ```bash
> cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/test/stress"
> MYSQL_PASSWORD=$(grep -oP '(?<=MYSQL_PASSWORD=).*' ../deploy/env/local.env) \
>   java -jar target/stress.jar seed --count 16000 --product snd_ess_pcs --secret-base sanduo-stress
> ```

- [ ] **Step 6: 手动冒烟——下行命令手动 ACK**

模拟器保持运行。开另一个终端，经网关下发命令（命令体无中文，可直接内联 `-d`）：
```bash
curl -s -X POST http://127.0.0.1:8000/api/command -H 'Content-Type: application/json' \
  -d '{"productKey":"snd_ess_pcs","deviceName":"sim-dev-000001","command":"set_soc","params":{"soc":80},"commandType":2,"timeoutMs":5000,"maxRetry":1,"createBy":0}'
```
Expected: 返回 JSON 含 `commandId`（记下该值）。

模拟器 REPL 应打印横幅：`↓ 收到下行命令: CommandMessage{commandId='<id>', command='set_soc', params={soc=80}}`，随后输入：
```
ack <commandId>
```
Expected: `已回 ACK <commandId> → SUCCESS`。

回查命令状态（网关侧应为 SUCCESS）：
```bash
curl -s http://127.0.0.1:8000/api/command/<commandId>
```

再验证 `autoack on` 自动回：
```
autoack on
```
再下发一条命令（重复上面 POST），Expected: 横幅多一行 `（autoack on，已自动回 SUCCESS）`，无需手动 ack，网关状态直接 SUCCESS。

最后输入 `quit`，Expected: `已退出`，进程退出且 broker 侧连接关闭（`/internal/broker/stats` 连接数回落）。

- [ ] **Step 7: 收尾——更新 README 或使用说明（可选但推荐）**

在 `test/sim-device/` 下加一段简短 `README.md`（构建/运行/命令表/参数），引用本设计文档与压测造数关系：
```markdown
# sim-device 交互式单设备模拟器

见 [sim-device 设计文档](../../docs/superpowers/specs/2026-08-07-sim-device-design.md)。

构建（需先 `cd sdk/java && mvn install`）：
    cd test/sim-device && mvn package

运行：
    ./sim-device.sh [--product pk] [--device dn] [--secret-base s | --secret hex] [--broker host:port] [--autoack]

命令：connect/disconnect/reconnect、report [k=v...]、event、lifecycle、status、ack、autoack、help、quit。
设备密钥派生与 test/stress seed 一致：hex(SHA-256(secret-base:index))，index 取 deviceName 数字后缀。
```
```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform"
git add test/sim-device/README.md
git commit -m "docs(test/sim-device): 构建/运行/命令表 README"
```
