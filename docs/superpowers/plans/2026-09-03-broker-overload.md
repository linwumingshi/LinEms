# Broker 连接数过载处置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 broker 节点过载时能对外声明"不接新客"（readiness DOWN）、以 CONNACK 0x03 替代 TCP 哑拒、设备重连退避带抖动，并将 `maxConnections` 阈值落到实测依据。

**Architecture:** 抽出共享连接计数 `ConnectionCounter`（准入与探针读同一份数）→ 新增过载判定 `BrokerLoadHealthIndicator`（连接数占比 + 堆内存占比任一超阈值 → readiness DOWN）→ `channelActive` 保留硬阈值 TCP 层关闭、`handleConnect` 增加软阈值 CONNACK 0x03 拒绝 → SDK 退避加半随机抖动 → 部署脚本补 JVM 堆上限与 fd 限制。集群负载均衡消费 readiness 的机制（两道闸）见 spec 附录 A，本计划不实现 LB 侧任何代码。

**Tech Stack:** Java 22 / Spring Boot 3 / Netty 4.1.108 / Micrometer；SDK 为独立 Maven 模块（Netty client）；JUnit 5 + AssertJ（broker 模块）/ JUnit 5（sdk 模块）；shell（deploy）。

## Global Constraints

- **相关 spec（必须先读）**：`docs/superpowers/specs/2026-09-03-broker-overload-design.md`（§5 详细设计、§7 陷阱、§8 错误处理、§9 测试策略）。
- **编码规范（用户铁律）**：关键代码必须中文注释且说明意图；方法签名变化必须同步 Javadoc；禁止行尾注释（`code; // x`），注释独立成行置于代码上方；禁用 `@Deprecated` API；遵循《阿里巴巴 Java 开发手册》。
- **格式门禁**：`backend/energy-mqtt-broker` 受 spring-javaformat 0.0.47 约束（tab 缩进）。**改 broker 代码后必须先跑 `spring-javaformat:apply`**，否则 validate 失败。`sdk/java` 与 `deploy/` 不受此约束（sdk 为 4 空格缩进）。
- **Maven（Windows）**：用 PowerShell 执行 `& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" ...`；broker 测试在 `backend/` 目录执行，sdk 测试在 `sdk/java/` 目录执行。离线 `-o`。
- **计数契约（不得破坏）**：`channelActive` 只增、`channelInactive` 统一减；任何拒绝分支禁止手动 decrement（历史 bug：手动回退 + close 触发 inactive 双重扣减导致准入穿透，回归测试 `ConnectionAdmissionCounterTest` 守护）。
- **配置项命名**：kebab-case；`BrokerProperties` 用嵌套 `@Data` 静态类（参照 `Tls`）。
- **commit 前缀**：`feat(broker):` / `fix(sdk):` / `chore(deploy):` / `test(broker):`，中文说明。
- 每个 Task 结束时若涉及 Spring 装配变化，检查是否有其它 `new MqttChannelInboundHandler(...)` 调用点需同步（当前 main 中无，handler 为 `@Component` 构造器注入，仅测试直接 new）。

---

### Task 1: 抽取共享连接计数 ConnectionCounter 并接入 handler

**Files:**
- Create: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/handler/ConnectionCounter.java`
- Modify: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/handler/MqttChannelInboundHandler.java`（:127 字段、:146-168 构造器、:187-194 `channelActive`、:206 `channelInactive`）
- Modify: `backend/energy-mqtt-broker/src/test/java/com/energyx/broker/handler/ConnectionAdmissionCounterTest.java`

**Interfaces:**
- Produces: `ConnectionCounter` —— `int get()` / `int incrementAndGet()` / `int decrementAndGet()`；`@Component`，Spring 单例。
- Consumes: handler 构造器**新增末位参数** `ConnectionCounter connectionCounter`（Task 4 的 `BrokerLoadHealthIndicator` 复用同一 Bean）。

- [ ] **Step 1: 创建 ConnectionCounter（先建测试目标类同包文件）**

```java
package com.energyx.broker.handler;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点接入连接计数（TCP 建连数，含未认证/半开连接）。
 *
 * <p>
 * 从 {@link MqttChannelInboundHandler} 抽出的共享计数：准入控制（硬/软阈值）与
 * {@code BrokerLoadHealthIndicator} 都要读取同一份"当前接入连接数"，保证 readiness 过载判定
 * 与准入拒绝基于同一个数，不会出现"探针说 DOWN 但准入还在放行"的错位。
 * </p>
 *
 * <p>
 * 计数契约：{@code channelActive} 只增、{@code channelInactive} 统一减；拒绝分支禁止手动回退，
 * 否则与 close 触发的 channelInactive 双重扣减导致计数负偏移、准入逐步失效。
 * </p>
 */
@Component
public class ConnectionCounter {

	private final AtomicInteger connections = new AtomicInteger();

	/**
	 * 当前接入连接数（含半开连接）。
	 * @return 当前计数
	 */
	public int get() {
		return connections.get();
	}

	/**
	 * 接入计数 +1（仅 {@code channelActive} 调用）。
	 * @return 递增后的计数
	 */
	public int incrementAndGet() {
		return connections.incrementAndGet();
	}

	/**
	 * 接入计数 -1（仅 {@code channelInactive} 调用）。
	 * @return 递减后的计数
	 */
	public int decrementAndGet() {
		return connections.decrementAndGet();
	}

}
```

- [ ] **Step 2: handler 换用共享计数**

`MqttChannelInboundHandler.java` 三处修改：

(a) 删除字段 `private final AtomicInteger rawConnections = new AtomicInteger();`（:127），替换为：

```java
	/** 共享接入连接计数（P2-9 抽取：准入与过载探针读同一份数，保证判定一致） */
	private final ConnectionCounter connectionCounter;
```

(b) 构造器签名（:146-151）在 `@Qualifier("brokerScheduler") ScheduledExecutorService scheduler` 之后追加参数，并在方法体（:164 附近 `this.scheduler = scheduler;` 之后）赋值：

```java
		this.connectionCounter = connectionCounter;
```

(c) 方法体引用替换（共 3 处符号替换，`replace_all`）：`rawConnections.incrementAndGet()` → `connectionCounter.incrementAndGet()`；`rawConnections.decrementAndGet()` → `connectionCounter.decrementAndGet()`；并删除不再使用的 `import java.util.concurrent.atomic.AtomicInteger;`（若全文件已无其它引用）。

- [ ] **Step 3: 更新既有回归测试**

`ConnectionAdmissionCounterTest.java`：

(a) `newHandler` 构造调用尾部补参数（:42-46）：

```java
		return new MqttChannelInboundHandler(mock(DeviceAuthService.class), mock(SessionRegistry.class),
				mock(SessionStore.class), mock(LocalSubscriberIndex.class), mock(MessageDeliverer.class),
				mock(LifecycleNotifier.class), mock(KafkaEventProducer.class), properties, mock(BrokerStats.class),
				mock(BrokerMetrics.class), mock(PublishRateLimiter.class), mock(ExecutorService.class),
				mock(ScheduledExecutorService.class), new ConnectionCounter());
```

(b) `counterOf` 反射目标字段名与返回类型（:55-59）改为 `connectionCounter` 并返回 `ConnectionCounter`：

```java
	private ConnectionCounter counterOf(MqttChannelInboundHandler handler) throws Exception {
		Field field = MqttChannelInboundHandler.class.getDeclaredField("connectionCounter");
		field.setAccessible(true);
		return (ConnectionCounter) field.get(handler);
	}
```

(c) 两个测试方法内的局部变量类型（:74、:101 附近）由 `AtomicInteger counter` 改为 `ConnectionCounter counter`（`counter.get()` 调用不变），并删除 `import java.util.concurrent.atomic.AtomicInteger;`。

- [ ] **Step 4: 运行回归测试确认全绿**

PowerShell，在 `backend/` 目录：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker -Dtest=ConnectionAdmissionCounterTest test
```

Expected: BUILD SUCCESS，2 个用例全绿（若报格式错先跑 `spring-javaformat:apply`）。

- [ ] **Step 5: Commit**

```bash
git add backend/energy-mqtt-broker/src/main/java/com/energyx/broker/handler/ConnectionCounter.java backend/energy-mqtt-broker/src/main/java/com/energyx/broker/handler/MqttChannelInboundHandler.java backend/energy-mqtt-broker/src/test/java/com/energyx/broker/handler/ConnectionAdmissionCounterTest.java
git commit -m "refactor(broker): 抽取 ConnectionCounter 共享连接计数，准入与探针读同一份数"
```

---

### Task 2: 过载配置项（BrokerProperties.overload + application.yml）

**Files:**
- Modify: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/config/BrokerProperties.java`（:48 `Tls` 类之后、:49 `maxConnections` 之前插入）
- Modify: `backend/energy-mqtt-broker/src/main/resources/application.yml`（:64 `max-connections` 之后）

**Interfaces:**
- Produces: `BrokerProperties.getOverload()` → `BrokerProperties.Overload`：`getSoftConnectionRatio()`（默认 0.9）/ `getHardConnectionRatio()`（默认 1.05）/ `getMaxHeapRatio()`（默认 0.85）。
- Consumes by: Task 4（探针判定）、Task 5（硬/软阈值准入）。

- [ ] **Step 1: BrokerProperties 增加嵌套配置类**

在 `Tls` 内部类结束（:47）之后、`maxConnections` 字段（:49）之前插入：

```java
	/** 过载处置配置（P2-9：双阈值准入 + readiness 探针判定） */
	private Overload overload = new Overload();

	@Data
	public static class Overload {

		/**
		 * 软阈值比例：接入连接数超过 {@code maxConnections × 该值} 后，新连接回 CONNACK 0x03
		 * SERVER_UNAVAILABLE（readiness 同步 DOWN）。
		 */
		private double softConnectionRatio = 0.9;

		/** 硬阈值比例：接入连接数超过 {@code maxConnections × 该值} 后，TCP 层直接关闭（连接风暴保命） */
		private double hardConnectionRatio = 1.05;

		/** 堆内存占比阈值：usedHeap / maxHeap 超过该值后 readiness DOWN（探针第二判定维度） */
		private double maxHeapRatio = 0.85;

	}
```

- [ ] **Step 2: application.yml 追加配置**

在 `energyx.broker.max-connections: 500000`（:64）之后追加：

```yaml
    # ---- 过载处置（P2-9）：双阈值准入 + readiness 探针 ----
    overload:
      soft-connection-ratio: 0.9   # 连接数占比软阈值：超过回 CONNACK 0x03 + readiness DOWN
      hard-connection-ratio: 1.05  # 连接数占比硬阈值：超过 TCP 层直接 close（防风暴期解码开销）
      max-heap-ratio: 0.85         # 堆内存占比阈值：超过 readiness DOWN
```

- [ ] **Step 3: 编译验证**

PowerShell，在 `backend/` 目录：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add backend/energy-mqtt-broker/src/main/java/com/energyx/broker/config/BrokerProperties.java backend/energy-mqtt-broker/src/main/resources/application.yml
git commit -m "feat(broker): 新增 overload 过载配置（软/硬阈值比例 + 堆内存占比）"
```

---

### Task 3: BrokerStats 软拒计数 admissionRedirect

**Files:**
- Modify: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/stats/BrokerStats.java`
- Test: `backend/energy-mqtt-broker/src/test/java/com/energyx/broker/stats/BrokerStatsTest.java`（新建）

**Interfaces:**
- Produces: `BrokerStats.recordAdmissionRedirect()`；快照含 `admissionRedirect` 键。`rejectedConnections` Javadoc 收敛为"硬拒"语义（与 `admissionRedirect` 区分，避免运维误读）。
- Consumes by: Task 5 软拒分支。

- [ ] **Step 1: 写失败测试**

`BrokerStatsTest.java`：

```java
package com.energyx.broker.stats;

import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.session.SessionRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 运行指标计数器快照验证。
 */
class BrokerStatsTest {

	/**
	 * 软拒计数必须进入运维快照，便于监控区分"软拒（可诊断）"与"硬拒（风暴）"。
	 */
	@Test
	void 软拒计数进入快照() {
		BrokerStats stats = new BrokerStats(mock(SessionRegistry.class), mock(LocalSubscriberIndex.class));
		stats.recordAdmissionRedirect();
		assertThat(stats.snapshot()).containsEntry("admissionRedirect", 1L);
	}

}
```

- [ ] **Step 2: 运行确认失败**

PowerShell，在 `backend/`：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker -Dtest=BrokerStatsTest test
```

Expected: FAIL —— snapshot 无 `admissionRedirect` 键。

- [ ] **Step 3: 实现**

`BrokerStats.java`：

(a) 在 `rejectedConnections` 字段（:27）下方追加（并把 :27 字段的 Javadoc 改为硬拒语义）：

```java
	/** 接入拒绝次数（硬拒：超硬阈值 TCP 层关闭，P2-9；认证超限走 authOverloadRejected，软拒走 admissionRedirect） */
	public final AtomicLong rejectedConnections = new AtomicLong();

	/** 软拒次数（连接数超软阈值回 CONNACK 0x03，P2-9）：与硬拒语义分离，供运维区分过载形态 */
	public final AtomicLong admissionRedirect = new AtomicLong();
```

(b) 在 `recordRejected()`（:87-90）之后追加：

```java
	/** 记录一次软拒（连接数超软阈值回 CONNACK 0x03，P2-9） */
	public void recordAdmissionRedirect() {
		admissionRedirect.incrementAndGet();
	}
```

(c) `snapshot()`（:145 附近 `map.put("rejectedConnections", rejectedConnections.get());` 之后）追加：

```java
		map.put("admissionRedirect", admissionRedirect.get());
```

- [ ] **Step 4: 运行确认通过**

同上命令，Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/energy-mqtt-broker/src/main/java/com/energyx/broker/stats/BrokerStats.java backend/energy-mqtt-broker/src/test/java/com/energyx/broker/stats/BrokerStatsTest.java
git commit -m "feat(broker): 软拒计数 admissionRedirect，与硬拒 rejectedConnections 语义分离"
```

---

### Task 4: 过载就绪探针 BrokerLoadHealthIndicator

**Files:**
- Create: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/stats/BrokerLoadHealthIndicator.java`
- Test: `backend/energy-mqtt-broker/src/test/java/com/energyx/broker/stats/BrokerLoadHealthIndicatorTest.java`（新建）
- Modify: `backend/energy-mqtt-broker/src/main/resources/application.yml`（management 段 :117-125）

**Interfaces:**
- Consumes: `ConnectionCounter`（Task 1）、`BrokerProperties.overload`（Task 2）。
- Produces: `/actuator/health/readiness` 聚合结果中的 `brokerLoad` 明细；启动期配置校验（软 < 硬）。
- Note: 本指示器**只挂 readiness group**（见 Step 3 yml），不参与 liveness。第一版判定维度仅连接数占比与堆内存占比（EventLoop 延迟/pending 水位列为第二阶段，spec §5.1）。

- [ ] **Step 1: 写失败测试**

`BrokerLoadHealthIndicatorTest.java`：

```java
package com.energyx.broker.stats;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.handler.ConnectionCounter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 过载就绪探针判定验证（连接数占比 + 堆内存占比 + 配置自检）。
 */
class BrokerLoadHealthIndicatorTest {

	/**
	 * 构造指定阈值配置（堆占比阈值给 1.5 以隔离连接数维度：堆永远不会触发）。
	 * @param maxConnections 单节点连接上限
	 * @param softRatio 连接数软阈值比例
	 * @return 配置对象
	 */
	private BrokerProperties props(long maxConnections, double softRatio) {
		BrokerProperties p = new BrokerProperties();
		p.setMaxConnections((int) maxConnections);
		p.getOverload().setSoftConnectionRatio(softRatio);
		p.getOverload().setMaxHeapRatio(1.5);
		return p;
	}

	/**
	 * 连接数未超软阈值 → UP；恰好等于阈值 → UP（判定用 &gt;）；超过 → DOWN。
	 */
	@Test
	void 连接数软阈值边界判定() {
		BrokerProperties p = this.props(100, 0.9);
		ConnectionCounter counter = new ConnectionCounter();
		for (int i = 0; i < 90; i++) {
			counter.incrementAndGet();
		}
		assertThat(new BrokerLoadHealthIndicator(counter, p).health().getStatus()).isEqualTo(Status.UP);
		counter.incrementAndGet();
		assertThat(new BrokerLoadHealthIndicator(counter, p).health().getStatus()).isEqualTo(Status.DOWN);
	}

	/**
	 * 堆内存占比超阈值（此处堆占比阈值 0，任何真实占用都超）→ DOWN，即使连接数为 0。
	 */
	@Test
	void 堆内存超阈值则DOWN() {
		BrokerProperties p = new BrokerProperties();
		p.setMaxConnections(1_000_000);
		p.getOverload().setSoftConnectionRatio(1.5);
		p.getOverload().setMaxHeapRatio(0.0);
		assertThat(new BrokerLoadHealthIndicator(new ConnectionCounter(), p).health().getStatus())
			.isEqualTo(Status.DOWN);
	}

	/**
	 * 软阈值不小于硬阈值时准入分层失效（软拒永不生效），配置自检必须抛异常快速失败。
	 */
	@Test
	void 软阈值不小于硬阈值则配置校验失败() {
		BrokerProperties p = new BrokerProperties();
		p.getOverload().setSoftConnectionRatio(0.9);
		p.getOverload().setHardConnectionRatio(0.8);
		BrokerLoadHealthIndicator indicator = new BrokerLoadHealthIndicator(new ConnectionCounter(), p);
		assertThatThrownBy(indicator::validate).isInstanceOf(IllegalStateException.class);
	}

}
```

- [ ] **Step 2: 运行确认失败**

PowerShell，在 `backend/`：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker -Dtest=BrokerLoadHealthIndicatorTest test
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现探针 + 挂 readiness group**

创建 `BrokerLoadHealthIndicator.java`：

```java
package com.energyx.broker.stats;

import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.handler.ConnectionCounter;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Broker 过载就绪探针（P2-9）。
 *
 * <p>
 * 通过 {@code /actuator/health/readiness} 对外暴露"能否接新连接"的二态判定：接入连接数占比
 * 或堆内存占比任一超过阈值即返回 DOWN，由 LB / 编排层摘除节点（只挡新连接，存量连接不受影响，
 * 属 graceful drain）。
 * </p>
 *
 * <p>
 * 设计约束（spec §7 坑 1）：本指示器**只挂 readiness group，绝不参与 liveness** —— 过载只应
 * "不接新客"，若误挂 liveness 会导致过载节点被重启、存量连接全部断开，自造灾难级重连风暴。
 * 判定用 {@code >}（等于阈值视为可接），与 Task 5 的准入软拒判定保持一致。
 * </p>
 */
@Component
public class BrokerLoadHealthIndicator implements HealthIndicator {

	private final ConnectionCounter connectionCounter;

	private final BrokerProperties properties;

	public BrokerLoadHealthIndicator(ConnectionCounter connectionCounter, BrokerProperties properties) {
		this.connectionCounter = connectionCounter;
		this.properties = properties;
	}

	/**
	 * 启动期配置自检：软阈值必须严格小于硬阈值，否则准入分层失效（软拒永不生效或风暴期仍在解码）。
	 * 违规直接抛异常 fail-fast，避免运行期行为不可预测。
	 */
	@PostConstruct
	void validate() {
		BrokerProperties.Overload overload = properties.getOverload();
		if (overload.getSoftConnectionRatio() >= overload.getHardConnectionRatio()) {
			throw new IllegalStateException("[Broker] overload.soft-connection-ratio(" + overload.getSoftConnectionRatio()
					+ ") 必须小于 hard-connection-ratio(" + overload.getHardConnectionRatio() + ")");
		}
	}

	/**
	 * 过载判定：连接数占比或堆内存占比任一超阈值即 DOWN，并回填各维度实测值明细。
	 * @return 含连接数/堆内存实测值的 Health
	 */
	@Override
	public Health health() {
		BrokerProperties.Overload overload = properties.getOverload();
		long maxConnections = properties.getMaxConnections();
		long connections = this.connectionCounter.get();
		Runtime runtime = Runtime.getRuntime();
		long usedHeap = runtime.totalMemory() - runtime.freeMemory();
		long maxHeap = runtime.maxMemory();
		boolean connOverload = isConnectionOverloaded(connections, maxConnections,
				overload.getSoftConnectionRatio());
		boolean heapOverload = isHeapOverloaded(usedHeap, maxHeap, overload.getMaxHeapRatio());
		if (connOverload || heapOverload) {
			return Health.down().withDetail("connections", connections)
				.withDetail("maxConnections", maxConnections)
				.withDetail("connectionRatio", ratio(connections, maxConnections))
				.withDetail("heapUsedBytes", usedHeap).withDetail("heapMaxBytes", maxHeap)
				.withDetail("heapRatio", ratio(usedHeap, maxHeap)).build();
		}
		return Health.up().withDetail("connections", connections)
			.withDetail("maxConnections", maxConnections)
			.withDetail("connectionRatio", ratio(connections, maxConnections))
			.withDetail("heapUsedBytes", usedHeap).withDetail("heapMaxBytes", maxHeap)
			.withDetail("heapRatio", ratio(usedHeap, maxHeap)).build();
	}

	/**
	 * 连接数占比过载判定（与准入软拒同一判定：超过才拒/才 DOWN）。
	 * @param connections 当前接入连接数
	 * @param maxConnections 单节点连接上限
	 * @param softRatio 软阈值比例
	 * @return 是否超过
	 */
	static boolean isConnectionOverloaded(long connections, long maxConnections, double softRatio) {
		return connections > (long) (maxConnections * softRatio);
	}

	/**
	 * 堆内存占比过载判定；maxHeap 为 0（异常采集）时按不过载处理，避免除零误判。
	 * @param usedHeap 已用堆字节数
	 * @param maxHeap 堆上限字节数
	 * @param heapRatio 阈值比例
	 * @return 是否超过
	 */
	static boolean isHeapOverloaded(long usedHeap, long maxHeap, double heapRatio) {
		return maxHeap > 0 && ratio(usedHeap, maxHeap) > heapRatio;
	}

	/**
	 * 占比计算；total 非正（异常）时返回 0，避免除零与 NaN 传染判定。
	 * @param part 分子
	 * @param total 分母
	 * @return 占比（0~1）
	 */
	private static double ratio(long part, long total) {
		return total <= 0 ? 0D : (double) part / total;
	}

}
```

`application.yml` 的 management 段（:117-125）改为：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health,info
  endpoint:
    health:
      probes:
        enabled: true
      # 自定义过载指示器只挂 readiness（include 会覆盖默认成员，必须写回 readinessState）；
      # liveness 端点保持仅进程存活判定，过载绝不触发重启
      group:
        readiness:
          include: readinessState,brokerLoad
```

- [ ] **Step 4: 运行确认通过**

同上命令，Expected: PASS（3 个用例）。

- [ ] **Step 5: Commit**

```bash
git add backend/energy-mqtt-broker/src/main/java/com/energyx/broker/stats/BrokerLoadHealthIndicator.java backend/energy-mqtt-broker/src/test/java/com/energyx/broker/stats/BrokerLoadHealthIndicatorTest.java backend/energy-mqtt-broker/src/main/resources/application.yml
git commit -m "feat(broker): 过载就绪探针 BrokerLoadHealthIndicator，挂 readiness group 不参与 liveness"
```

---

### Task 5: 双阈值准入（硬拒保命 + 软拒可诊断）

**Files:**
- Modify: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/handler/MqttChannelInboundHandler.java`（`channelActive` :187-194；`handleConnect` 在重复 CONNECT 检查块之后插入软拒）
- Test: `backend/energy-mqtt-broker/src/test/java/com/energyx/broker/handler/SoftAdmissionTest.java`（新建）

**Interfaces:**
- Consumes: `connectionCounter`（Task 1）、`properties.getOverload()`（Task 2）、`stats.recordAdmissionRedirect()`（Task 3）。
- Produces: 硬阈值行为（超 `maxConnections × hard-connection-ratio` → TCP close）；软阈值行为（超 `maxConnections × soft-connection-ratio` → CONNACK 0x03 + close）。

- [ ] **Step 1: 写失败测试**

`SoftAdmissionTest.java`：

```java
package com.energyx.broker.handler;

import com.energyx.broker.auth.DeviceAuthService;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.lifecycle.LifecycleNotifier;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.ratelimit.PublishRateLimiter;
import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.routing.MessageDeliverer;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.stats.BrokerMetrics;
import com.energyx.broker.stats.BrokerStats;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 双阈值准入的软拒分支验证（P2-9）。
 *
 * <p>
 * 构造真实 handler + 真实 {@link ConnectionCounter}，用反射把计数压入"软阈值之上、硬阈值之下"
 * 的区间，再向 EmbeddedChannel 写入 CONNECT 报文，断言收到 CONNACK 0x03 且连接被关闭。
 * </p>
 */
class SoftAdmissionTest {

	/**
	 * 构造真实计数已到软区间（95 = max100 × 0.95，介于软 0.9 与硬 1.05 之间）的 handler。
	 * @return 待测 handler（内部计数已置位）
	 */
	private MqttChannelInboundHandler handlerAtSoftZone() {
		BrokerProperties properties = new BrokerProperties();
		properties.setMaxConnections(100);
		properties.getOverload().setSoftConnectionRatio(0.9);
		properties.getOverload().setHardConnectionRatio(1.05);
		ConnectionCounter counter = new ConnectionCounter();
		for (int i = 0; i < 95; i++) {
			counter.incrementAndGet();
		}
		return new MqttChannelInboundHandler(mock(DeviceAuthService.class), mock(SessionRegistry.class),
				mock(SessionStore.class), mock(LocalSubscriberIndex.class), mock(MessageDeliverer.class),
				mock(LifecycleNotifier.class), mock(KafkaEventProducer.class), properties, mock(BrokerStats.class),
				mock(BrokerMetrics.class), mock(PublishRateLimiter.class), mock(ExecutorService.class),
				mock(ScheduledExecutorService.class), counter);
	}

	/**
	 * 构造 MQTT 3.1.1 CONNECT 报文。
	 * @param clientId 设备标识（本平台 clientId 即设备身份）
	 * @return CONNECT 报文
	 */
	private MqttConnectMessage connectMessage(String clientId) {
		return MqttMessageBuilders.connect().clientId(clientId).protocolVersion(4).cleanSession(true)
			.keepAlive(60).username("device").password("secret".getBytes()).build();
	}

	/**
	 * 软区间内发 CONNECT：channel 必须收到 CONNACK 0x03 后关闭；计数随后归位（channelInactive 减回）。
	 */
	@Test
	void 软阈值区间内CONNECT被回0x03拒绝() {
		MqttChannelInboundHandler handler = this.handlerAtSoftZone();
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		// 写入 CONNECT 前本条连接已计入硬阈值的 increment（真实建连行为）
		channel.writeInbound(this.connectMessage("dev-soft-reject"));
		MqttConnAckMessage ack = channel.readOutbound();
		assertThat(ack).as("必须回 CONNACK").isNotNull();
		assertThat(ack.variableHeader().connectReturnCode())
			.as("软拒必须为 SERVER_UNAVAILABLE(0x03)").isEqualTo(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
		assertThat(channel.isOpen()).as("软拒后连接必须关闭").isFalse();
		channel.close();
		channel.finishAndReleaseAll();
	}

}
```

- [ ] **Step 2: 运行确认失败**

PowerShell，在 `backend/`：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker -Dtest=SoftAdmissionTest test
```

Expected: FAIL —— CONNECT 未被拒（当前 handler 无软拒逻辑，会继续走认证路径；认证服务为 mock 返回 null，行为不定但绝无 0x03 ack）。

- [ ] **Step 3: 实现双阈值**

`MqttChannelInboundHandler.java`：

(a) `channelActive` 整体替换为（:187-194；Javadoc 同步更新"硬阈值"语义并保留计数契约说明）：

```java
	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// 硬阈值准入（P2-9）：超过 maxConnections × hard-connection-ratio 直接在 TCP 层关闭、
		// 不解析 MQTT 报文 —— 连接风暴时避免为每个被拒连接付出解码开销。
		// 计数契约：本方法只 increment，回退统一由 channelInactive 承担（close 必触发该回调，
		// 手动回退会造成双重扣减、计数负偏移、准入逐步失效，回归见 ConnectionAdmissionCounterTest）
		long hardLimit = (long) (properties.getMaxConnections() * properties.getOverload().getHardConnectionRatio());
		if (connectionCounter.incrementAndGet() > hardLimit) {
			stats.recordRejected();
			log.warn("[Broker] 超过硬阈值 {}，拒绝 {}", hardLimit, ctx.channel().remoteAddress());
			ctx.close();
		}
	}
```

(b) `handleConnect` 软拒插入：定位"重复 CONNECT 检查"的 if 块结束处（特征文本 `log.warn("[Broker] 重复 CONNECT，按规范关断 clientId={} remote={}"...` 的收尾大括号之后），在其后插入：

```java
		// 软阈值准入（P2-9 过载处置）：接入连接数超过 maxConnections × soft-connection-ratio 时不哑拒，
		// 回 CONNACK 0x03 SERVER_UNAVAILABLE 再关闭 —— 让设备端能区分"服务器过载"与"网络故障"，
		// 从而采用针对性的退避策略。置于认证信号量之前：过载时不再消耗认证资源。
		// 计数回退仍统一由 channelInactive 承担，此处禁手动 decrement
		long softLimit = (long) (properties.getMaxConnections() * properties.getOverload().getSoftConnectionRatio());
		if (connectionCounter.get() > softLimit) {
			stats.recordAdmissionRedirect();
			log.warn("[Broker] 超过软阈值 {}，回 CONNACK 0x03 拒绝 clientId={} remote={}", softLimit, clientId,
					channel.remoteAddress());
			this.sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE, false);
			channel.close();
			return;
		}
```

注：`clientId`、`channel` 均为 `handleConnect` 方法头已声明的局部变量（`handleConnect` :385-389），插入点处仍在作用域。

- [ ] **Step 4: 运行测试确认通过 + 全量回归**

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker -Dtest=SoftAdmissionTest,ConnectionAdmissionCounterTest test
```

Expected: 两组用例全绿（SoftAdmissionTest 1 个 + ConnectionAdmissionCounterTest 2 个）。

- [ ] **Step 5: Commit**

```bash
git add backend/energy-mqtt-broker/src/main/java/com/energyx/broker/handler/MqttChannelInboundHandler.java backend/energy-mqtt-broker/src/test/java/com/energyx/broker/handler/SoftAdmissionTest.java
git commit -m "feat(broker): 连接数准入改双阈值，软阈值回 CONNACK 0x03（可诊断拒绝），硬阈值 TCP 层保命"
```

---

### Task 6: SDK 重连退避加随机抖动

**Files:**
- Modify: `sdk/java/src/main/java/com/energyx/device/MqttDevice.java`（:412-437 重连区；缩进为 **4 空格**，无 spring-javaformat 约束）
- Test: `sdk/java/src/test/java/com/energyx/device/MqttDeviceReconnectDelayTest.java`（新建）

**Interfaces:**
- Produces: 包级静态方法 `static long reconnectDelayMillis(long backoffMs, long maxBackoffMs, int attempt)`；`scheduleReconnect` 改用它。
- Note: 本项目设备端只认单域名，抖动不是"换节点"，而是**打散集群内设备重连时刻**，防止同步回撞形成周期性尖峰（spec §5.3）。

- [ ] **Step 1: 写失败测试**

`MqttDeviceReconnectDelayTest.java`（用 JUnit 原生断言，SDK 无 AssertJ 依赖保证）：

```java
package com.energyx.device;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重连延迟半随机抖动验证：延迟必须落在 [capped/2, capped] 且同档位下有分散。
 */
class MqttDeviceReconnectDelayTest {

    /**
     * 退避上限内所有档位的延迟都须落在 [capped/2, capped]。
     */
    @Test
    void delayInHalfRange() {
        for (int attempt = 0; attempt <= 8; attempt++) {
            long capped = Math.min(1_000L * (1L << Math.min(attempt, 5)), 60_000L);
            for (int i = 0; i < 200; i++) {
                long delay = MqttDevice.reconnectDelayMillis(1_000, 60_000, attempt);
                assertTrue(delay >= capped / 2 && delay <= capped,
                        "attempt=" + attempt + " delay=" + delay + " 须落在 [" + (capped / 2) + ", " + capped + "]");
            }
        }
    }

    /**
     * 同一档位重复采样必须出现多个不同值，证明抖动真实生效而非恒等延迟。
     */
    @Test
    void delaySpreads() {
        Set<Long> samples = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            samples.add(MqttDevice.reconnectDelayMillis(1_000, 60_000, 3));
        }
        assertTrue(samples.size() > 1, "同一档位 200 次采样应有多个不同延迟");
    }

    /**
     * 退避上限为 0 时必须安全返回 0（无除零/越界）。
     */
    @Test
    void zeroBackoffSafe() {
        assertEquals(0, MqttDevice.reconnectDelayMillis(0, 0, 0));
    }

}
```

- [ ] **Step 2: 运行确认失败**

PowerShell，在 `sdk/java/`：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -Dtest=MqttDeviceReconnectDelayTest test
```

Expected: 编译失败（方法不存在）。

- [ ] **Step 3: 实现抖动**

`MqttDevice.java`：

(a) import 区追加（按字母序插入 `java.util.concurrent.ThreadLocalRandom`）。

(b) 在 `scheduleReconnect(int attempt)` 方法上方新增静态方法（沿用 4 空格缩进与中文 Javadoc）：

```java
    /**
     * 计算带随机抖动的重连延迟（毫秒）。
     *
     * <p>
     * 在指数退避上限内先取 capped，再取 [capped/2, capped] 半区间随机：保留一半固定基准保证退避
     * 仍随次数增长，另一半随机打散大规模设备集群内的重连时刻，防止断网恢复后设备同步回撞形成
     * 周期性尖峰。
     * </p>
     *
     * @param backoffMs    基础退避步长（毫秒）
     * @param maxBackoffMs 退避上限（毫秒）
     * @param attempt      第几次重连（0 起）
     * @return [capped/2, capped] 内的延迟毫秒数；capped 为 0 时返回 0
     */
    static long reconnectDelayMillis(long backoffMs, long maxBackoffMs, int attempt) {
        long capped = Math.min(backoffMs * (1L << Math.min(attempt, 5)), maxBackoffMs);
        return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
    }
```

(c) `scheduleReconnect` 内的延迟计算（原 :433-436）整体替换为：

```java
        long delay = MqttDevice.reconnectDelayMillis((long) config.reconnectBackoffMs(),
                (long) config.reconnectMaxBackoffMs(), attempt);
```

- [ ] **Step 4: 运行确认通过**

同上命令，Expected: 3 个用例全绿。

- [ ] **Step 5: Commit**

```bash
git add sdk/java/src/main/java/com/energyx/device/MqttDevice.java sdk/java/src/test/java/com/energyx/device/MqttDeviceReconnectDelayTest.java
git commit -m "fix(sdk): 重连退避加半随机抖动，打散大规模重连时刻防同步回撞"
```

---

### Task 7: 部署脚本硬化（JVM 堆上限 + fd 限制）

**Files:**
- Modify: `deploy/scripts/start-stack.sh`（:9 `set -uo pipefail` 之后、:114-129 `start_one()`）

- [ ] **Step 1: 修改脚本**

(a) 在 `set -uo pipefail`（:9）之后追加 fd 限制：

```bash
# 文件句柄上限：支撑 broker 数十万长连接的 fd 需求；权限不足时静默忽略，勿中断启动
ulimit -n 1048576 2>/dev/null || true
```

(b) `start_one()` 内（:126 `nohup java -jar "$jar" ...` 行）改为按服务注入 JVM 参数：

```bash
  # Broker 承载设备长连接，需显式堆上限（否则走 JVM 默认=物理内存 1/4，OOM 先于优雅拒绝）；
  # BROKER_XMX 环境变量可覆盖，默认 4g，按 §5.5 实测拐点调整
  local jvm_opts=""
  if [ "$name" = "energy-mqtt-broker" ]; then
    jvm_opts="-Xms1g -Xmx${BROKER_XMX:-4g}"
  fi
  nohup java $jvm_opts -jar "$jar" >"$LOG_DIR/${name}.log" 2>&1 &
```

- [ ] **Step 2: 语法与静态验证**

```bash
bash -n deploy/scripts/start-stack.sh
grep -n "Xmx\|ulimit -n" deploy/scripts/start-stack.sh
```

Expected: `bash -n` 无输出（语法 OK）；grep 命中 2 处（`-Xmx${BROKER_XMX:-4g}` 与 `ulimit -n 1048576`）。

- [ ] **Step 3: Commit**

```bash
git add deploy/scripts/start-stack.sh
git commit -m "chore(deploy): 启动脚本补 fd 上限与 broker JVM 堆上限，防 OOM/fd 先于优雅拒绝"
```

---

### Task 8: 全量回归 + readiness 冒烟验收

**Files:**（仅验证，不改代码；实测阈值部分产出报告并回填配置）

- [ ] **Step 1: broker 全量测试 + 格式门禁**

PowerShell，在 `backend/`：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker spring-javaformat:apply
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" -pl energy-mqtt-broker test
```

Expected: BUILD SUCCESS（既有 45 测试 + 本次新增全部通过）。

- [ ] **Step 2: sdk 全量测试**

PowerShell，在 `sdk/java/`：

```powershell
& "D:\Program Files\Maven\bin\mvn.cmd" -o "-Dmaven.repo.local=D:\Program Files\maven-repo" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: 单节点 readiness 冒烟（验收标准 §12 第 2-3 条）**

1. 起单节点 broker（复用 `deploy/scripts/start-stack.sh` 或直接启动 jar）。
2. 确认探针端点可用且链路正确：
   - `curl -s http://127.0.0.1:8082/actuator/health/liveness` → 含 `"status":"UP"`
   - `curl -s http://127.0.0.1:8082/actuator/health/readiness` → 含 `brokerLoad` 明细与 `"status":"UP"`
   - `curl -s http://127.0.0.1:8082/actuator/health` → 聚合端点含 `brokerLoad`
3. 用 `test/sim-device/sim-device.sh` 连满：临时把 `max-connections` 调小（如 5，经 `BROKER_XMX` 之外的启动参数或直接改 yml）后连 6 个设备 → 第 6 个应收到 CONNACK 0x03（sim-device 日志可见拒绝原因），且已连接的 5 个持续收发心跳不断。
4. 验证 readiness 转 DOWN 时 liveness 恒 UP。

- [ ] **Step 4: 实测阈值并回填（验收标准 §12 第 5 条；可在本 Task 或另开会话执行）**

用 `test/sim-device/sim-device.sh` + `/internal/broker/stats` + 监控，按 spec §5.5 四步阶梯加压（1k → 5k → 1w → 5w → 10w），每档稳定 3 分钟记录 p99 心跳延迟 / GC 停顿 / 堆占用，取拐点 60~70% 回填 `application.yml` 的 `max-connections`、`-Xmx` 与 `BROKER_XMX`，并回填 spec 附录"实测结论"小节。

- [ ] **Step 5: 最终 Commit（如 Step 4 有配置回填）**

```bash
git add backend/energy-mqtt-broker/src/main/resources/application.yml docs/superpowers/specs/2026-09-03-broker-overload-design.md
git commit -m "chore(broker): max-connections 按 sim-device 实测拐点回填并记录实测结论"
```
