package com.energyx.broker.handler;

import com.energyx.broker.auth.AuthResult;
import com.energyx.broker.auth.DeviceAuthService;
import com.energyx.broker.auth.DeviceCredential;
import com.energyx.common.enums.DeviceStatus;
import com.energyx.broker.auth.TopicAcl;
import com.energyx.broker.config.BrokerProperties;
import com.energyx.broker.config.NettyServerConfig;
import com.energyx.broker.lifecycle.LifecycleNotifier;
import com.energyx.broker.mqtt.KafkaEventProducer;
import com.energyx.broker.ratelimit.PublishRateLimiter;
import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.routing.MessageDeliverer;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import com.energyx.broker.session.InboundPublish;
import com.energyx.broker.session.InflightMessage;
import com.energyx.broker.session.MqttSubscription;
import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.stats.BrokerMetrics;
import com.energyx.broker.stats.BrokerStats;
import com.energyx.common.constant.KafkaTopicConstant;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttConnectVariableHeader;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * MQTT 报文分发处理器（@Sharable 单例，跨 channel 共享，channel 态全部放 channel attribute）。
 *
 * <p>
 * 处理矩阵（MQTT 3.1.1 §2.2 + v5 兼容子集）： <pre>
 * CONNECT/PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP/SUBSCRIBE/UNSUBSCRIBE/PINGREQ/DISCONNECT
 * </pre> 职责边界：协议握手、ACL、QoS 状态机、路由分发；业务无关（上行报文不落库，Phase 5 摄取）。
 *
 * <p>
 * 线程模型（P0 修复后严格执行）：EventLoop 上只做内存操作与 channel 写出；
 * 认证（Redis/MySQL）、连接锁、会话恢复、持久化、在线续期等阻塞操作一律经 brokerExecutor 剥离； 连接锁抢占与 sessionPresent
 * 判定在业务线程完成后再回投 eventLoop 注册会话。
 * </p>
 *
 * <p>
 * 可靠性契约：QoS1/2 上行的 PUBACK/PUBCOMP 在 Kafka 路由持久化回调成功后才发送； 路由失败关闭连接，设备重连重传（at-least-once）。
 * </p>
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class MqttChannelInboundHandler extends ChannelInboundHandlerAdapter {

	private static final AttributeKey<Session> SESSION_ATTR = AttributeKey.valueOf("mqtt.session");

	private static final AttributeKey<String> DEVICE_KEY_ATTR = AttributeKey.valueOf("mqtt.deviceKey");

	private final DeviceAuthService authService;

	private final SessionRegistry sessionRegistry;

	private final SessionStore sessionStore;

	private final LocalSubscriberIndex subscriberIndex;

	private final MessageDeliverer deliverer;

	private final LifecycleNotifier lifecycleNotifier;

	private final KafkaEventProducer kafkaProducer;

	private final BrokerProperties properties;

	private final BrokerStats stats;

	private final BrokerMetrics metrics;

	private final ExecutorService executor;

	private final ScheduledExecutorService scheduler;

	private final PublishRateLimiter rateLimiter;

	private final AtomicInteger rawConnections = new AtomicInteger();

	/**
	 * 认证并发信号量（P2-8）：控制同时进行中的认证数，超限快速拒绝新连接防风暴
	 */
	private final Semaphore authSlots;

	/**
	 * 会话恢复并发信号量（重连风暴防护）：控制同时执行的恢复任务数，超限延迟重试
	 */
	private final Semaphore sessionRestoreSlots;

	/**
	 * 构造 MQTT 报文分发处理器。
	 *
	 * <p>
	 * 注入全部依赖（认证、会话、路由、生命周期、统计、限速等），并按配置初始化认证与会话恢复两套并发信号量， 防止连接风暴与重连风暴打满业务线程池。
	 * </p>
	 */
	public MqttChannelInboundHandler(DeviceAuthService authService, SessionRegistry sessionRegistry,
			SessionStore sessionStore, LocalSubscriberIndex subscriberIndex, MessageDeliverer deliverer,
			LifecycleNotifier lifecycleNotifier, KafkaEventProducer kafkaProducer, BrokerProperties properties,
			BrokerStats stats, BrokerMetrics metrics, PublishRateLimiter rateLimiter,
			@Qualifier("brokerExecutor") ExecutorService executor,
			@Qualifier("brokerScheduler") ScheduledExecutorService scheduler) {
		this.authService = authService;
		this.sessionRegistry = sessionRegistry;
		this.sessionStore = sessionStore;
		this.subscriberIndex = subscriberIndex;
		this.deliverer = deliverer;
		this.lifecycleNotifier = lifecycleNotifier;
		this.kafkaProducer = kafkaProducer;
		this.properties = properties;
		this.stats = stats;
		this.metrics = metrics;
		this.rateLimiter = rateLimiter;
		this.executor = executor;
		this.scheduler = scheduler;
		// 并发信号量下限保护（至少 1），避免配置为 0 时所有连接/恢复被直接拒绝
		this.authSlots = new Semaphore(Math.max(1, properties.getAuthMaxConcurrent()));
		this.sessionRestoreSlots = new Semaphore(Math.max(1, properties.getSessionRestoreMaxConcurrent()));
	}

	// ---------------- 连接生命周期 ----------------

	/**
	 * 连接建立回调（IO 线程）：接入计数 + 准入控制。
	 *
	 * <p>
	 * 超过单节点最大连接数（{@code energyx.broker.max-connections}）直接关闭新连接并记录拒绝指标， 防止连接风暴打满节点。
	 * </p>
	 *
	 * <p>
	 * 计数契约：本方法只负责 {@code incrementAndGet}，回退统一由
	 * {@link #channelInactive(ChannelHandlerContext)} 承担——{@code ctx.close()} 必然触发
	 * channelInactive，若此处再手动回退会与其叠加造成重复扣减， 使计数持续负偏移、 {@code maxConnections} 准入逐步失效（每次拒绝少算
	 * 1）。 回归见 {@code ConnectionAdmissionCounterTest}。
	 * </p>
	 */
	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// 准入控制：超过单节点上限拒绝新连接（计数回退交给 channelInactive，避免与 close 触发的回调重复扣减）
		if (rawConnections.incrementAndGet() > properties.getMaxConnections()) {
			stats.recordRejected();
			log.warn("[Broker] 超过最大连接数 {}，拒绝 {}", properties.getMaxConnections(), ctx.channel().remoteAddress());
			ctx.close();
		}
	}

	/**
	 * 连接关闭回调（IO 线程）：统一收尾入口。
	 *
	 * <p>
	 * 注销会话、释放连接锁；按会话类型分流——瞬时会话（clean/expiry=0）直接删除全部 Redis 状态，
	 * 持久会话保存会话元数据与幽灵订阅（支撑离线队列）；随后异步发离线通知，并按优雅/非优雅断开 决定遗嘱投递（延迟遗嘱在窗口内重连则取消）。
	 * </p>
	 */
	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		rawConnections.decrementAndGet();
		Session session = ctx.channel().attr(SESSION_ATTR).getAndSet(null);
		ctx.channel().attr(DEVICE_KEY_ATTR).set(null);
		if (session == null) {
			// 未认证连接直接断开（无会话上下文可清理）
			return;
		}
		session.getPendingWrites().clear();
		String deviceKey = session.getDeviceKey();
		// O(1) 注销：仅当注册表中仍是本 channel 的会话才移除（防旧连接晚到误删新会话）
		sessionRegistry.unregisterIfChannelMatches(deviceKey, ctx.channel());

		if (session.isSuperseded()) {
			// 同 clientId 新连接已接管：本连接静默清理，不触发离线事件/持久化/锁释放
			log.info("[Broker] 旧连接被新连接取代 deviceKey={}", deviceKey);
			return;
		}

		boolean graceful = session.isDisconnectedGracefully();
		// P1-11：v5 Session Expiry Interval=0（或 clean 会话）→ 断开即删；>0 → 按过期时间持久化
		long expiry = session.getSessionExpirySeconds();
		boolean transientSession = session.isCleanSession() || expiry <= 0;
		if (transientSession) {
			subscriberIndex.removeAll(deviceKey);
			executor.execute(() -> {
				sessionStore.deleteSession(deviceKey);
				sessionStore.deleteSubscriptions(deviceKey);
				sessionStore.deleteInflight(deviceKey);
				sessionStore.releaseConnLockIfOwner(deviceKey, properties.getNodeId());
			});
		}
		else {
			// 持久会话：幽灵订阅保留（支撑离线队列），持久化会话元数据（含过期时间）+ 订阅
			executor.execute(() -> {
				try {
					sessionStore.saveSession(deviceKey, properties.getNodeId(), false, expiry);
					sessionStore.saveSubscriptions(deviceKey, new HashSet<>(session.getSubscriptions().values()));
				}
				catch (Exception e) {
					log.error("[Broker] 持久会话保存失败 deviceKey={}", deviceKey, e);
				}
				sessionStore.releaseConnLockIfOwner(deviceKey, properties.getNodeId());
			});
		}

		DeviceCredential cred = this.attrCredential(session);
		if (cred != null) {
			String reason = graceful ? "NORMAL" : "HEARTBEAT_TIMEOUT";
			String remoteIp = session.getRemoteIp();
			// 离线通知含 Redis 删除，剥离到业务线程
			executor.execute(() -> lifecycleNotifier.notifyOffline(cred, reason, remoteIp));
			// 遗嘱处理（Redis 持久化，节点宕机不丢）：
			// 非优雅断开 → 投递遗嘱（delay=0 立即 / delay>0 调度，窗口内重连取消）→ 删除 Redis 遗嘱；
			// 优雅断开 → 不投递，直接删除 Redis 遗嘱（视为正常下线，不再补投）
			Session.MqttWill will = session.getWill();
			if (!graceful && will != null) {
				if (will.getDelaySeconds() <= 0) {
					deliverer.deliver(will.getTopic(), will.getPayload(), will.getQos(), will.isRetain(), null);
					executor.execute(() -> sessionStore.deleteWill(deviceKey));
				}
				else {
					String willDeviceKey = deviceKey;
					scheduler.schedule(() -> {
						Session current = sessionRegistry.get(willDeviceKey);
						if (current != null && current.isOnline()) {
							log.info("[Broker] Will Delay 窗口内设备已重连，取消遗嘱 deviceKey={}", willDeviceKey);
							return;
						}
						deliverer.deliver(will.getTopic(), will.getPayload(), will.getQos(), will.isRetain(), null);
						executor.execute(() -> sessionStore.deleteWill(willDeviceKey));
					}, will.getDelaySeconds(), TimeUnit.SECONDS);
				}
			}
			else if (graceful) {
				// 优雅断开：遗嘱不投递，清除 Redis 持久化遗嘱（下次重连不再补投）
				executor.execute(() -> sessionStore.deleteWill(deviceKey));
			}
		}
		log.info("[Broker] 连接关闭 deviceKey={} reason={}", deviceKey, graceful ? "NORMAL" : "ABNORMAL");
	}

	/**
	 * 用户事件回调（IO 线程）：处理空闲超时。
	 *
	 * <p>
	 * 收到 {@link IdleStateEvent}（keepalive 阈值内无读写）判定心跳超时，关闭连接交由 {@link #channelInactive}
	 * 走统一离线收尾；其余事件上抛父类。
	 * </p>
	 */
	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof IdleStateEvent) {
			Session session = ctx.channel().attr(SESSION_ATTR).get();
			log.warn("[Broker] 心跳超时断开 deviceKey={} remote={}", session == null ? "?" : session.getDeviceKey(),
					ctx.channel().remoteAddress());
			// 关闭连接，由 channelInactive 统一触发离线事件与持久化收尾
			ctx.close();
		}
		else {
			super.userEventTriggered(ctx, evt);
		}
	}

	/**
	 * channel 恢复可写：冲刷该会话的背压挂起队列（在 eventLoop 上触发）
	 */
	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session != null && ctx.channel().isWritable()) {
			deliverer.flushPending(session);
		}
		super.channelWritabilityChanged(ctx);
	}

	/**
	 * 通道异常回调（IO 线程）：记录告警后关闭连接。
	 *
	 * <p>
	 * 不在此处区分异常类型——统一关连接，由 {@link #channelInactive} 执行离线收尾（设备重连重传兜底）。
	 * </p>
	 */
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		log.warn("[Broker] 通道异常关闭 deviceKey={} err={}", session == null ? "?" : session.getDeviceKey(),
				cause.getMessage());
		ctx.close();
	}

	// ---------------- 报文分发 ----------------

	/**
	 * 报文分发入口（Netty 回调，IO 线程执行）。
	 *
	 * <p>
	 * 按 MQTT 报文类型分发到对应处理分支；仅识别 {@link MqttMessage} 子类，其余类型（如握手遗留对象）直接忽略。 无论分发是否成功，报文引用在
	 * finally 中统一释放（{@link ReferenceCountUtil#release}），避免堆外内存泄漏。
	 * </p>
	 */
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msgObj) {
		// 仅处理 MQTT 报文，其余类型（如 TLS 握手遗留对象）直接忽略
		if (!(msgObj instanceof MqttMessage msg)) {
			return;
		}
		try {
			MqttMessageType type = msg.fixedHeader().messageType();
			switch (type) {
				case CONNECT -> this.handleConnect(ctx, (MqttConnectMessage) msg);
				case PUBLISH -> this.handlePublish(ctx, (MqttPublishMessage) msg);
				case PUBACK -> this.handlePubAck(ctx, (MqttPubAckMessage) msg);
				case PUBREC -> this.handlePubRec(ctx, msg);
				case PUBREL -> this.handlePubRel(ctx, msg);
				case PUBCOMP -> this.handlePubComp(ctx, msg);
				case SUBSCRIBE -> this.handleSubscribe(ctx, (MqttSubscribeMessage) msg);
				case UNSUBSCRIBE -> this.handleUnsubscribe(ctx, (MqttUnsubscribeMessage) msg);
				case PINGREQ -> this.handlePingReq(ctx);
				case DISCONNECT -> this.handleDisconnect(ctx, msg);
				default -> log.debug("[Broker] 忽略报文类型 {}", type);
			}
		}
		finally {
			// 无论分发是否成功，统一释放报文引用，防止堆外内存（ByteBuf）泄漏
			ReferenceCountUtil.release(msgObj);
		}
	}

	// ---------------- CONNECT ----------------

	/**
	 * CONNECT 握手处理（IO 线程入口，慢路径剥离）。
	 *
	 * <p>
	 * 协议版本/空 clientId/重复 CONNECT 三关在 IO 线程直接拒绝；认证（Redis/MySQL）、连接锁抢占、 sessionPresent
	 * 判定等阻塞操作经 {@code brokerExecutor} 异步执行，仅最终会话注册与 CONNACK 回投 eventLoop， 保证 IO
	 * 线程零阻塞（P2-8 认证风暴防护：并发认证受信号量限流）。
	 * </p>
	 */
	private void handleConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
		Channel channel = ctx.channel();
		MqttConnectVariableHeader vh = msg.variableHeader();
		String clientId = msg.payload().clientIdentifier() == null ? "" : msg.payload().clientIdentifier();
		int version = vh.version();

		if (version != 3 && version != 4 && version != 5) {
			this.sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION, false);
			channel.close();
			return;
		}
		// P2-4 决策：MQTT 3.1.1/5.0 允许 cleanSession=1 时空 clientId 由 Broker 分配，
		// 但本平台认证为「clientId 即设备身份」（HMAC username 内嵌 clientId，凭据按 clientId 绑定），
		// 空 clientId 无法通过认证且构成匿名接入风险，故维持拒绝（安全约束优先于协议完备）。
		if (clientId.isEmpty()) {
			this.sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED, false);
			channel.close();
			return;
		}
		// 协议合规（MQTT-3.1.0-2）：同一连接上第二个 CONNECT 必须关断
		if (channel.attr(SESSION_ATTR).get() != null) {
			log.warn("[Broker] 重复 CONNECT，按规范关断 clientId={} remote={}", clientId, channel.remoteAddress());
			channel.close();
			return;
		}

		String username = msg.payload().userName();
		String password = msg.payload().passwordInBytes() == null ? ""
				: new String(msg.payload().passwordInBytes(), StandardCharsets.UTF_8);

		// ---- v5 连接属性协商：Session Expiry Interval / Receive Maximum / Maximum Packet Size
		// ----
		// 会话过期秒数：-1 = 未指定，沿用默认（7 天或 clean 会话 0）
		long sessionExpirySeconds = -1;
		// 接收并发上限：-1 = 未指定，用协议上限 65535（再经 inflight 配置收敛）
		int receiveMaximum = -1;
		// 最大报文尺寸：0 = 未指定（v3），不限制下行报文大小
		int maxPacketSize = 0;
		if (version == 5) {
			MqttProperties props = msg.variableHeader().properties();
			MqttProperties.IntegerProperty expiry = (MqttProperties.IntegerProperty) props
				.getProperty(MqttProperties.MqttPropertyType.SESSION_EXPIRY_INTERVAL.value());
			if (expiry != null) {
				sessionExpirySeconds = expiry.value();
			}
			MqttProperties.IntegerProperty recvMax = (MqttProperties.IntegerProperty) props
				.getProperty(MqttProperties.MqttPropertyType.RECEIVE_MAXIMUM.value());
			if (recvMax != null) {
				receiveMaximum = Math.max(1, recvMax.value());
			}
			MqttProperties.IntegerProperty maxPkt = (MqttProperties.IntegerProperty) props
				.getProperty(MqttProperties.MqttPropertyType.MAXIMUM_PACKET_SIZE.value());
			if (maxPkt != null && maxPkt.value() != null) {
				maxPacketSize = Math.max(1, maxPkt.value());
			}
		}

		// 遗嘱（v3 从 payload 提取；v5 will delay 读取 will properties，0 则立即投递）
		Session.MqttWill will = null;
		if (vh.isWillFlag()) {
			String willTopic = msg.payload().willTopic();
			if (willTopic != null && !willTopic.isEmpty()) {
				// P2-3：用 willMessageInBytes 提取原始字节（二进制安全），
				// willMessage() 按 UTF-8 String 解码会损坏二进制载荷
				byte[] willBytes = msg.payload().willMessageInBytes();
				int willDelay = 0;
				if (version == 5 && msg.payload().willProperties() != null) {
					MqttProperties.IntegerProperty delay = (MqttProperties.IntegerProperty) msg.payload()
						.willProperties()
						.getProperty(MqttProperties.MqttPropertyType.WILL_DELAY_INTERVAL.value());
					if (delay != null) {
						willDelay = Math.max(0, delay.value());
					}
				}
				will = new Session.MqttWill(willTopic, willBytes == null ? new byte[0] : willBytes, vh.willQos(),
						vh.isWillRetain(), willDelay);
			}
		}

		ConnectParams params = new ConnectParams(version, vh.keepAliveTimeSeconds(), vh.isCleanSession(), will,
				this.remoteIp(channel), sessionExpirySeconds, receiveMaximum, maxPacketSize);

		// 慢路径全部在业务线程：认证（Redis/MySQL）→ 连接锁（Redis）→ sessionPresent 判定（Redis），
		// 仅最终会话注册与 CONNACK 回投 eventLoop（IO 线程零阻塞）
		// P2-8 认证风暴防护：并发认证数超限（信号量 3s 拿不到许可）快速拒绝新连接，保护 executor 不被打满
		boolean acquired;
		try {
			acquired = authSlots.tryAcquire(3, TimeUnit.SECONDS);
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			acquired = false;
		}
		if (!acquired) {
			stats.recordAuthOverloadRejected();
			log.warn("[Auth] 认证并发超限拒绝 clientId={} remote={}", clientId, channel.remoteAddress());
			channel.eventLoop().execute(() -> {
				this.sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE, false);
				channel.close();
			});
			return;
		}
		executor.execute(() -> {
			AuthResult result;
			try {
				result = authService.authenticate(clientId, username, password);
			}
			catch (Exception e) {
				log.error("[Auth] 认证异常 clientId={}", clientId, e);
				result = AuthResult.deny(3, false, "认证服务异常");
			}
			finally {
				// 认证（含后续 mTLS/连接锁）完成释放并发许可
				authSlots.release();
			}
			if (!result.isAllowed()) {
				stats.recordAuthFailure();
				AuthResult denied = result;
				channel.eventLoop().execute(() -> {
					this.sendConnAck(channel, this.returnCode(denied.getConnackCode()), false);
					channel.close();
				});
				return;
			}
			DeviceCredential cred = result.getCredential();
			String deviceKey = cred.getDeviceKey();

			// P1-12 mTLS：设备证书 CN 必须等于 clientId（身份绑定；信任链由 TLS 握手层校验）
			if (properties.getTls().isClientAuth() && !this.verifyClientCert(channel, clientId)) {
				stats.recordAuthFailure();
				channel.eventLoop().execute(() -> {
					this.sendConnAck(channel, MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED, false);
					channel.close();
				});
				return;
			}

			// 跨节点连接锁：被远端占用 → 通知远端踢线，本节点接管
			if (!sessionStore.tryAcquireConnLock(deviceKey, properties.getNodeId())) {
				String owner = sessionStore.getConnLockOwner(deviceKey);
				if (owner != null && !owner.equals(properties.getNodeId())) {
					// 节点心跳判定：owner 已死（宕机/失联）则跳过无效踢线，直接接管；存活才发 KICK
					if (sessionStore.isNodeAlive(owner)) {
						this.kickRemote(owner, deviceKey);
					}
					else {
						log.info("[Broker] owner 节点心跳消失视为宕机，跳过踢线直接接管 deviceKey={} owner={}", deviceKey, owner);
					}
				}
				sessionStore.overwriteConnLock(deviceKey, properties.getNodeId());
			}

			boolean sessionPresent = !params.cleanSession && sessionStore.existsSession(deviceKey);
			channel.eventLoop().execute(() -> this.completeConnect(ctx, params, cred, sessionPresent));
		});
	}

	/**
	 * 完成连接建立（认证通过后回投 eventLoop 执行）。
	 *
	 * <p>
	 * 职责：踢本节点旧连接 → 构建并注册 Session（写入 channel attribute）→ 按 keepalive 重设 idle 阈值 → 发送
	 * CONNACK → 异步执行会话恢复与上线通知。v3/v5 的 CONNACK 差异与 v5 属性协商在此收敛。
	 * </p>
	 */
	private void completeConnect(ChannelHandlerContext ctx, ConnectParams p, DeviceCredential cred,
			boolean sessionPresent) {
		Channel channel = ctx.channel();
		// 连接可能已在认证期间被对端关闭，回投后先校验活性再继续
		if (!channel.isActive()) {
			return;
		}
		String deviceKey = cred.getDeviceKey();

		// 踢本节点旧连接（同 clientId）
		Session old = sessionRegistry.get(deviceKey);
		if (old != null && old.getChannel() != channel) {
			old.setSuperseded(true);
			old.getChannel().close();
			sessionRegistry.unregister(deviceKey);
		}

		Session session = new Session(deviceKey, channel, p.version, p.cleanSession);
		session.putAttr(Session.SessionAttr.DEVICE_ID, cred.getDeviceId());
		session.putAttr(Session.SessionAttr.TENANT_ID, cred.getTenantId());
		session.putAttr(Session.SessionAttr.PRODUCT_KEY, cred.getProductKey());
		session.putAttr(Session.SessionAttr.DEVICE_NAME, cred.getDeviceName());
		session.putAttr(Session.SessionAttr.DEVICE_STATUS, cred.getDeviceStatus());
		session.setWill(p.will);
		session.setRemoteIp(p.remoteIp);
		// P1-11 v5 属性：Session Expiry Interval（-1 未指定 → 默认 7 天；0 → 断开即过期）
		if (p.sessionExpirySeconds >= 0) {
			session.setSessionExpirySeconds(p.sessionExpirySeconds);
		}
		else {
			session.setSessionExpirySeconds(p.cleanSession ? 0 : properties.getSessionTtlSeconds());
		}
		// P1-11 v5 Receive Maximum（-1 未指定 → 协议上限 65535，由 inflight 配置再收敛）
		if (p.receiveMaximum > 0) {
			session.setReceiveMaximum(p.receiveMaximum);
		}
		// v5 属性协商：Maximum Packet Size（0 = 未指定/v3，不限制下行报文大小）
		if (p.maxPacketSize > 0) {
			session.setMaxPacketSize(p.maxPacketSize);
		}
		channel.attr(SESSION_ATTR).set(session);
		channel.attr(DEVICE_KEY_ATTR).set(deviceKey);
		sessionRegistry.register(session);

		// keepalive：>0 按 1.5× 重设 IdleStateHandler；=0 按协议不启用超时，移除预置 idle handler
		if (p.keepAliveSeconds > 0) {
			this.replaceIdleHandler(channel, p.keepAliveSeconds);
		}
		else {
			ChannelPipeline pipeline = channel.pipeline();
			if (pipeline.get(NettyServerConfig.IDLE_HANDLER_NAME) != null) {
				pipeline.remove(NettyServerConfig.IDLE_HANDLER_NAME);
			}
		}

		// 干净会话重连：清除残留幽灵订阅（上一连接遗留）
		if (p.cleanSession) {
			subscriberIndex.removeAll(deviceKey);
		}

		if (p.version == 5) {
			// v5 CONNACK 能力声明（属性协商）：Maximum QoS=2、Retain Available=1（本 broker 能力）
			this.sendConnAckV5(channel, MqttConnectReturnCode.CONNECTION_ACCEPTED, sessionPresent);
		}
		else {
			this.sendConnAck(channel, MqttConnectReturnCode.CONNECTION_ACCEPTED, sessionPresent);
		}
		stats.recordAccepted();
		log.info("[Broker] 设备上线 deviceKey={} version={} remote={}", deviceKey, p.version, p.remoteIp);

		// 异步恢复 + 上线通知：Redis/Kafka 阻塞操作全部走业务线程池。
		// 重连风暴防护（可靠性）：恢复任务受信号量并发限流，超限延迟重试而非丢弃——
		// 海量设备同时重连（断电恢复/基站批量上线）时恢复任务排开执行，不把 executor 打满。
		executor.execute(() -> this.runSessionRestore(p, session, cred, 0));
	}

	/**
	 * 会话恢复任务（限流 + 延迟重试）。
	 *
	 * <p>
	 * 恢复内容包括：clean 会话清理残留 / 持久会话订阅加载、inflight 续传、离线队列补发、 上线通知。并发超过
	 * {@code session-restore-max-concurrent} 时按配置延迟重试（默认 2s， 最多 3
	 * 次），限流期间不丢弃恢复任务——重连风暴下表现为「连接先建立、会话逐步恢复」， 而非恢复任务被线程池拒绝策略直接丢弃。
	 * </p>
	 */
	private void runSessionRestore(ConnectParams p, Session session, DeviceCredential cred, int attempt) {
		if (attempt >= properties.getSessionRestoreMaxAttempts()) {
			log.warn("[Broker] 会话恢复重试耗尽（限流持续）deviceKey={}，下次重连再恢复", session.getDeviceKey());
			return;
		}
		// 无参 tryAcquire 不抛 InterruptedException，非阻塞快速判定
		if (!sessionRestoreSlots.tryAcquire()) {
			log.info("[Broker] 会话恢复并发受限，{}s 后重试 deviceKey={} attempt={}",
					properties.getSessionRestoreRetryDelaySeconds(), session.getDeviceKey(), attempt + 1);
			scheduler.schedule(() -> this.runSessionRestore(p, session, cred, attempt + 1),
					properties.getSessionRestoreRetryDelaySeconds(), TimeUnit.SECONDS);
			return;
		}
		try {
			String deviceKey = session.getDeviceKey();
			try {
				if (p.cleanSession) {
					sessionStore.deleteSession(deviceKey);
					sessionStore.deleteSubscriptions(deviceKey);
					sessionStore.deleteInflight(deviceKey);
				}
				else {
					// 遗嘱持久化（节点宕机不丢）：先补投上次连接未投递的遗嘱（如节点宕机场景），
					// 再以本次 CONNECT 声明的遗嘱覆盖；正常非优雅断开已投递并删除，此处 loadWill 为空
					Session.MqttWill persistedWill = sessionStore.loadWill(deviceKey);
					if (persistedWill != null) {
						log.info("[Broker] 补投上次未投递的遗嘱 deviceKey={} topic={}", deviceKey, persistedWill.getTopic());
						deliverer.deliver(persistedWill.getTopic(), persistedWill.getPayload(), persistedWill.getQos(),
								persistedWill.isRetain(), null);
						sessionStore.deleteWill(deviceKey);
					}
					sessionStore.saveWill(deviceKey, session.getWill());

					Set<MqttSubscription> subs = sessionStore.loadSubscriptions(deviceKey);
					for (MqttSubscription sub : subs) {
						session.getSubscriptions().put(sub.getTopicFilter(), sub);
						subscriberIndex.add(session, sub.getTopicFilter(), sub.getQos());
					}
					deliverer.resendInflight(session);
					deliverer.deliverOfflineQueue(session);
				}
			}
			catch (Exception e) {
				log.error("[Broker] 会话恢复失败 deviceKey={}", deviceKey, e);
			}
			try {
				lifecycleNotifier.notifyOnline(cred, p.remoteIp);
			}
			catch (Exception e) {
				log.error("[Broker] 上线通知失败 deviceKey={}", deviceKey, e);
			}
		}
		finally {
			sessionRestoreSlots.release();
		}
	}

	/**
	 * 远程踢线：通知设备当前所在节点断开连接（跨节点连接锁被占用时的接管路径）。
	 *
	 * <p>
	 * 通过 {@code mqtt.broadcast} 广播 KICK 信封（各节点唯一消费组，按 sourceNode 去重）， 由 owner
	 * 节点据此关闭旧连接，本节点随后完成连接锁接管。
	 * </p>
	 * @param ownerNode 设备当前所在节点（用于日志定位，实际由广播送达）
	 * @param deviceKey 目标设备键（{productKey}_{deviceName}）
	 */
	private void kickRemote(String ownerNode, String deviceKey) {
		try {
			RouterEnvelope envelope = RouterEnvelope.kick(properties.getNodeId(), deviceKey);
			// 阶段 2：KICK 走 mqtt.broadcast 广播通道（每节点唯一消费组，sourceNode 去重）
			byte[] payload = RouterEnvelopeCodec.encode(envelope);
			kafkaProducer.sendBytes(KafkaTopicConstant.MQTT_BROADCAST, deviceKey, payload);
		}
		catch (Exception e) {
			log.warn("[Broker] 远程踢线信封发送失败 deviceKey={} owner={}", deviceKey, ownerNode, e);
		}
	}

	/**
	 * mTLS 设备证书校验（P1-12）：TLS 握手完成后（CONNECT 报文到达即已握手），读取对端证书 链，校验叶子证书 subject CN 与
	 * clientId 一致（证书签发/吊销由 CA 体系负责，见 deploy/scripts/gen-mqtt-certs.sh -c）。握手未完成/证书缺失一律拒绝。
	 */
	private boolean verifyClientCert(Channel channel, String clientId) {
		try {
			// 取握手完成后的对端证书链（CONNECT 到达即已 TLS 握手，可直接读取）
			SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
			if (sslHandler == null) {
				log.warn("[mTLS] 非 TLS 连接尝试设备接入，拒绝 clientId={}", clientId);
				return false;
			}
			Certificate[] chain = sslHandler.engine().getSession().getPeerCertificates();
			if (chain == null || chain.length == 0) {
				log.warn("[mTLS] 对端未提供证书 clientId={}", clientId);
				return false;
			}
			// 取叶子证书（设备证书）的 subject CN，与 clientId 比对完成身份绑定
			if (!(chain[0] instanceof X509Certificate leaf)) {
				return false;
			}
			String cn = this.extractCn(leaf.getSubjectX500Principal().getName());
			boolean ok = clientId.equals(cn);
			if (!ok) {
				log.warn("[mTLS] 证书 CN={} 与 clientId={} 不匹配，拒绝接入", cn, clientId);
			}
			return ok;
		}
		catch (Exception e) {
			log.warn("[mTLS] 证书校验异常 clientId={} err={}", clientId, e.getMessage());
			return false;
		}
	}

	/**
	 * 从 X500 名称提取 CN（LDAP 序，兼容 "CN=xx,O=yy" 与 RFC2253 "O=yy,CN=xx"）
	 */
	private String extractCn(String dn) {
		try {
			for (Rdn rdn : new LdapName(dn).getRdns()) {
				if ("CN".equalsIgnoreCase(rdn.getType())) {
					return String.valueOf(rdn.getValue());
				}
			}
		}
		catch (Exception ignore) {
			// 空
		}
		return null;
	}

	// ---------------- PUBLISH（设备上行） ----------------

	/**
	 * 设备上行 PUBLISH 处理。
	 *
	 * <p>
	 * 前置校验：会话存在、topic ACL（{@link TopicAcl#canPublish}）、单设备发布限速（P2-7）。 QoS 分派：QoS0
	 * 直接路由；QoS1 路由持久化确认后回 PUBACK（at-least-once）；QoS2 先入站缓存，收到 PUBREL
	 * 才路由（恰好一次）。所有可能失败的分支以「关连接迫使设备重传」兜底，不丢报文语义。
	 * </p>
	 */
	private void handlePublish(ChannelHandlerContext ctx, MqttPublishMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			ctx.close();
			return;
		}
		String topic = msg.variableHeader().topicName();
		int qos = msg.fixedHeader().qosLevel().value();
		boolean retain = msg.fixedHeader().isRetain();
		int packetId = msg.variableHeader().packetId();
		byte[] payload = new byte[msg.payload().readableBytes()];
		msg.payload().getBytes(msg.payload().readerIndex(), payload);

		DeviceCredential cred = this.attrCredential(session);
		if (!TopicAcl.canPublish(cred, topic)) {
			log.warn("[ACL] 拒绝越权发布 deviceKey={} topic={}", session.getDeviceKey(), topic);
			stats.recordRejected();
			ctx.close();
			return;
		}

		session.touch();
		this.maybeRenewOnline(session, cred);
		// P2-7 单设备发布限速：超限 QoS0 丢弃、QoS1/2 关连接迫使设备节流
		if (!rateLimiter.tryAcquire(session.getDeviceKey())) {
			stats.recordRateLimited();
			if (qos > 0) {
				log.warn("[RateLimit] 发布超限关连接 deviceKey={} topic={} qos={}", session.getDeviceKey(), topic, qos);
				ctx.close();
			}
			else {
				log.warn("[RateLimit] QoS0 发布超限丢弃 deviceKey={} topic={}", session.getDeviceKey(), topic);
			}
			return;
		}
		Channel channel = ctx.channel();
		// P1-10 链路追踪：同一上报报文的处理链日志共用 traceId（deviceKey+毫秒+序号）
		String traceId = this.deviceKeyOf(session) + "-" + (System.currentTimeMillis() & 0xFFFFF) + "-"
				+ Integer.toHexString(System.identityHashCode(msg));
		switch (qos) {
			case 0 -> deliverer.deliver(topic, payload, 0, retain, null);
			case 1 -> {
				// PUBACK 推迟到 Kafka 路由持久化确认之后；路由失败关连接，设备重连重传（at-least-once）
				long publishStart = System.nanoTime();
				deliverer.deliver(topic, payload, 1, retain, null, () -> {
					metrics.recordPubAckLatency(publishStart);
					channel.eventLoop().execute(() -> {
						if (channel.isActive()) {
							this.sendPubAck(channel, packetId);
						}
					});
				}, () -> channel.eventLoop().execute(() -> {
					log.error("[Deliver] 路由失败关连接迫使重传 traceId={} deviceKey={} topic={}", traceId, session.getDeviceKey(),
							topic);
					channel.close();
				}));
			}
			case 2 -> {
				// 收到 PUBREL 才路由（恰好一次入站）
				session.getInboundQos2().put(packetId, new InboundPublish(topic, payload, qos));
				this.sendPubRec(channel, packetId);
			}
			default -> log.warn("[Broker] 非法 QoS={} deviceKey={}", qos, session.getDeviceKey());
		}
		stats.recordIncoming();
	}

	/**
	 * 会话 deviceKey（工具方法，避免重复 get）
	 */
	private String deviceKeyOf(Session session) {
		return session.getDeviceKey();
	}

	/**
	 * PUBACK（QoS1 出站完成确认）：从出站 inflight 表移除并异步清理 Redis 持久化记录。
	 */
	private void handlePubAck(ChannelHandlerContext ctx, MqttPubAckMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			return;
		}
		int packetId = msg.variableHeader().messageId();
		// QoS1 出站完成：移除内存 inflight；命中则异步清理 Redis 持久化记录（断连续传兜底）
		InflightMessage inflight = session.getOutboundInflight().remove(packetId);
		if (inflight != null) {
			this.asyncRemoveInflight(session.getDeviceKey(), packetId);
		}
	}

	/**
	 * PUBREC（QoS2 出站第一阶段确认）：状态推进到 AWAITING_PUBCOMP 并持久化后发 PUBREL； 收到重复 PUBREC 视为 PUBREL
	 * 丢失，重发 PUBREL 防御。
	 */
	private void handlePubRec(ChannelHandlerContext ctx, MqttMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			return;
		}
		int packetId = this.messageId(msg);
		InflightMessage inflight = session.getOutboundInflight().get(packetId);
		if (inflight == null) {
			return;
		}
		if (inflight.getState() == InflightMessage.STATE_AWAITING_PUBREC) {
			inflight.setState(InflightMessage.STATE_AWAITING_PUBCOMP);
			session.getOutboundInflight().put(packetId, inflight);
			this.asyncSaveInflight(session.getDeviceKey(), inflight);
			this.sendPubRel(ctx.channel(), packetId);
		}
		else if (inflight.getState() == InflightMessage.STATE_AWAITING_PUBCOMP) {
			// 防御分支：PUBREL 可能丢失，收到重复 PUBREC 时重发 PUBREL
			this.sendPubRel(ctx.channel(), packetId);
		}
	}

	/**
	 * PUBREL（QoS2 入站第二阶段）：从入站缓存取出报文正式路由，持久化确认后回 PUBCOMP（恰好一次）； 缓存缺失视为重复 PUBREL，直接幂等确认。
	 */
	private void handlePubRel(ChannelHandlerContext ctx, MqttMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			return;
		}
		int packetId = this.messageId(msg);
		InboundPublish inbound = session.getInboundQos2().remove(packetId);
		Channel channel = ctx.channel();
		if (inbound != null) {
			// PUBCOMP 推迟到路由持久化确认之后；失败关连接，设备重连后重发 PUBREL 完成恰好一次
			deliverer.deliver(inbound.topic(), inbound.payload(), inbound.qos(), false, null,
					() -> channel.eventLoop().execute(() -> {
						if (channel.isActive()) {
							this.sendPubComp(channel, packetId);
						}
					}), () -> channel.eventLoop().execute(() -> {
						log.error("[Deliver] QoS2 入站路由失败关连接 deviceKey={} topic={}", session.getDeviceKey(),
								inbound.topic());
						channel.close();
					}));
		}
		else {
			// 重复 PUBREL（首次已完成或会话重启）：直接确认，幂等
			this.sendPubComp(channel, packetId);
		}
		stats.recordIncoming();
	}

	/**
	 * PUBCOMP（QoS2 出站最终确认）：从出站 inflight 表移除并异步清理 Redis 持久化记录。
	 */
	private void handlePubComp(ChannelHandlerContext ctx, MqttMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			return;
		}
		int packetId = this.messageId(msg);
		// QoS2 最终确认：移除内存 inflight 并异步清理 Redis，完成恰好一次投递闭环
		session.getOutboundInflight().remove(packetId);
		this.asyncRemoveInflight(session.getDeviceKey(), packetId);
	}

	// ---------------- SUBSCRIBE / UNSUBSCRIBE ----------------

	/**
	 * SUBSCRIBE 处理：逐 filter 校验 ACL，合法者登记本地订阅索引并回成功 QoS，非法者回 0x80 拒绝码；
	 * 持久会话异步落库，最后按顺序投递保留消息（SUBACK 之后，符合 MQTT 顺序语义）。
	 */
	private void handleSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			ctx.close();
			return;
		}
		DeviceCredential cred = this.attrCredential(session);
		int packetId = msg.variableHeader().messageId();
		List<MqttTopicSubscription> subs = msg.payload().topicSubscriptions();
		List<Integer> returnCodes = new ArrayList<>(subs.size());
		List<String> allowedFilters = new ArrayList<>();

		for (MqttTopicSubscription sub : subs) {
			String filter = sub.topicFilter();
			int reqQos = sub.qualityOfService().value();
			if (TopicAcl.canSubscribe(cred, filter)) {
				returnCodes.add(reqQos);
				allowedFilters.add(filter);
				session.getSubscriptions().put(filter, new MqttSubscription(filter, reqQos));
				subscriberIndex.add(session, filter, reqQos);
			}
			else {
				// 拒绝订阅：回 0x80 失败码（MQTT 3.1.1 §3.8.4）
				returnCodes.add(0x80);
				log.warn("[ACL] 拒绝越权订阅 deviceKey={} filter={}", session.getDeviceKey(), filter);
			}
		}
		this.sendSubAck(ctx.channel(), packetId, returnCodes);

		if (!session.isCleanSession()) {
			this.persistSubscriptions(session);
		}
		// 保留消息投递（SUBACK 之后再发，符合 MQTT 顺序语义）
		for (String filter : allowedFilters) {
			deliverer.deliverRetainedOnSubscribe(session, filter);
		}
	}

	/**
	 * UNSUBSCRIBE 处理：移除本地订阅索引与会话订阅表，回 UNSUBACK；持久会话异步落库。
	 */
	private void handleUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		// 无会话上下文的 UNSUBSCRIBE 视为非法/已失效连接，直接关断
		if (session == null) {
			ctx.close();
			return;
		}
		int packetId = msg.variableHeader().messageId();
		// 本地索引与内存订阅表同步移除，回 UNSUBACK；持久会话异步落库保持 Redis 一致
		for (String filter : msg.payload().topics()) {
			session.getSubscriptions().remove(filter);
			subscriberIndex.remove(session.getDeviceKey(), filter);
		}
		this.sendUnsubAck(ctx.channel(), packetId);
		if (!session.isCleanSession()) {
			this.persistSubscriptions(session);
		}
	}

	/**
	 * 会话订阅持久化（异步写 Redis，失败仅告警不阻断主流程）。
	 */
	private void persistSubscriptions(Session session) {
		executor.execute(() -> {
			try {
				sessionStore.saveSubscriptions(session.getDeviceKey(),
						new HashSet<>(session.getSubscriptions().values()));
			}
			catch (Exception e) {
				log.error("[Broker] 订阅持久化失败 deviceKey={}", session.getDeviceKey(), e);
			}
		});
	}

	// ---------------- PINGREQ / DISCONNECT ----------------

	/**
	 * PINGREQ 处理：刷新会话活跃时间并异步续期在线状态，回 PINGRESP。
	 */
	private void handlePingReq(ChannelHandlerContext ctx) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session == null) {
			ctx.close();
			return;
		}
		// 刷新活跃时间并续期在线态，回 PINGRESP 维持 keepalive 心跳
		session.touch();
		this.maybeRenewOnline(session, this.attrCredential(session));
		ctx.channel()
			.writeAndFlush(new MqttMessage(
					new MqttFixedHeader(MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0)));
	}

	/**
	 * DISCONNECT 处理：标记优雅断开（不投遗嘱、正常持久化），随后关闭连接触发 channelInactive 收尾。
	 */
	private void handleDisconnect(ChannelHandlerContext ctx, MqttMessage msg) {
		Session session = ctx.channel().attr(SESSION_ATTR).get();
		if (session != null) {
			session.setDisconnectedGracefully(true);
		}
		// 优雅断开：channelInactive 负责持久化会话与离线事件（不投遗嘱）
		ctx.close();
	}

	// ---------------- 工具 ----------------

	/**
	 * 按需续期设备在线状态（Redis 写剥离到业务线程）。
	 *
	 * <p>
	 * 仅当会话自上次续期超过阈值（{@link Session#shouldRenewOnline}）才发起，避免每个上行报文都打 Redis。
	 * </p>
	 */
	private void maybeRenewOnline(Session session, DeviceCredential cred) {
		if (cred != null && session.shouldRenewOnline()) {
			executor.execute(() -> lifecycleNotifier.renewOnline(cred));
		}
	}

	/**
	 * 异步移除 Redis 中的出站 inflight 记录（业务线程执行，不阻塞 IO）。
	 */
	private void asyncRemoveInflight(String deviceKey, int packetId) {
		executor.execute(() -> sessionStore.removeInflight(deviceKey, packetId));
	}

	/**
	 * 异步持久化出站 inflight 记录（业务线程执行，不阻塞 IO）。
	 */
	private void asyncSaveInflight(String deviceKey, InflightMessage inflight) {
		executor.execute(() -> sessionStore.saveInflight(deviceKey, inflight));
	}

	/**
	 * 按 CONNECT keepalive 重设空闲超时：阈值为 keepalive×1.5（MQTT-3.1.2-24）， 已有预置 idle handler
	 * 则原位替换，否则插入到 MQTT handler 之前。
	 */
	private void replaceIdleHandler(Channel channel, int keepAliveSeconds) {
		// 空闲阈值取 keepalive×1.5（MQTT-3.1.2-24）：给对端心跳留出冗余，避免误判超时断连
		int readerIdle = Math.max(1, (int) Math.ceil(keepAliveSeconds * 1.5));
		ChannelPipeline pipeline = channel.pipeline();
		IdleStateHandler idle = new IdleStateHandler(readerIdle, 0, 0);
		if (pipeline.get(NettyServerConfig.IDLE_HANDLER_NAME) != null) {
			pipeline.replace(NettyServerConfig.IDLE_HANDLER_NAME, NettyServerConfig.IDLE_HANDLER_NAME, idle);
		}
		else {
			// 首次注入：置于业务 handler 之前，避免影响既有 pipeline 结构
			pipeline.addBefore(NettyServerConfig.MQTT_HANDLER_NAME, NettyServerConfig.IDLE_HANDLER_NAME, idle);
		}
		log.debug("[Broker] keepalive={}s → idle 阈值 {}s", keepAliveSeconds, readerIdle);
	}

	/**
	 * 发送 v3 CONNACK（QoS0，含 sessionPresent 标志）。
	 * @param channel 目标连接
	 * @param code MQTT 3.1.1 连接返回码
	 * @param sessionPresent 是否恢复既有会话（持久会话重连为 true）
	 */
	private void sendConnAck(Channel channel, MqttConnectReturnCode code, boolean sessionPresent) {
		channel.writeAndFlush(new MqttConnAckMessage(
				new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
				new MqttConnAckVariableHeader(code, sessionPresent)));
	}

	/**
	 * v5 CONNACK：携带服务端能力声明（Maximum QoS=2、Retain Available=1，见 v5 属性协商）
	 * @param channel 目标连接
	 * @param code MQTT 5.0 连接返回码
	 * @param sessionPresent 是否恢复既有会话（持久会话重连为 true）
	 */
	private void sendConnAckV5(Channel channel, MqttConnectReturnCode code, boolean sessionPresent) {
		MqttProperties props = new MqttProperties();
		props.add(new MqttProperties.IntegerProperty(MqttProperties.MqttPropertyType.MAXIMUM_QOS.value(), 2));
		props.add(new MqttProperties.IntegerProperty(MqttProperties.MqttPropertyType.RETAIN_AVAILABLE.value(), 1));
		channel.writeAndFlush(new MqttConnAckMessage(
				new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
				new MqttConnAckVariableHeader(code, sessionPresent, props)));
	}

	/**
	 * 发送 PUBACK（QoS1 入站确认，路由持久化成功后调用）。
	 * @param channel 目标连接
	 * @param packetId 确认的报文标识符
	 */
	private void sendPubAck(Channel channel, int packetId) {
		channel.writeAndFlush(new MqttPubAckMessage(
				new MqttFixedHeader(MqttMessageType.PUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
				MqttMessageIdVariableHeader.from(packetId)));
	}

	/**
	 * 发送 PUBREC（QoS2 入站第一阶段确认，报文暂存入站缓存）。
	 * @param channel 目标连接
	 * @param packetId 确认的报文标识符
	 */
	private void sendPubRec(Channel channel, int packetId) {
		channel.writeAndFlush(
				new MqttMessage(new MqttFixedHeader(MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0),
						MqttMessageIdVariableHeader.from(packetId)));
	}

	/**
	 * 发送 PUBREL（QoS2 出站第二阶段确认，QoS 固定为 1，见 MQTT-4.3.3-2）。
	 * @param channel 目标连接
	 * @param packetId 确认的报文标识符
	 */
	private void sendPubRel(Channel channel, int packetId) {
		channel.writeAndFlush(
				new MqttMessage(new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0),
						MqttMessageIdVariableHeader.from(packetId)));
	}

	/**
	 * 发送 PUBCOMP（QoS2 最终确认，入站报文路由成功后调用）。
	 * @param channel 目标连接
	 * @param packetId 确认的报文标识符
	 */
	private void sendPubComp(Channel channel, int packetId) {
		channel.writeAndFlush(
				new MqttMessage(new MqttFixedHeader(MqttMessageType.PUBCOMP, false, MqttQoS.AT_MOST_ONCE, false, 0),
						MqttMessageIdVariableHeader.from(packetId)));
	}

	/**
	 * 发送 SUBACK（逐 filter 返回授权 QoS 或 0x80 拒绝码）。
	 * @param channel 目标连接
	 * @param packetId SUBACK 对应的报文标识符
	 * @param codes 与请求顺序一致的结果码列表（合法 QoS 或 0x80）
	 */
	private void sendSubAck(Channel channel, int packetId, List<Integer> codes) {
		channel.writeAndFlush(new MqttSubAckMessage(
				new MqttFixedHeader(MqttMessageType.SUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
				MqttMessageIdVariableHeader.from(packetId), new MqttSubAckPayload(codes)));
	}

	/**
	 * 发送 UNSUBACK（Netty 无专用类，以 MqttMessage + messageId 构造）。
	 * @param channel 目标连接
	 * @param packetId UNSUBACK 对应的报文标识符
	 */
	private void sendUnsubAck(Channel channel, int packetId) {
		// Netty 无 MqttUnsubAckMessage 类，UNSUBACK = MqttMessage + messageId
		channel.writeAndFlush(
				new MqttMessage(new MqttFixedHeader(MqttMessageType.UNSUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
						MqttMessageIdVariableHeader.from(packetId)));
	}

	/**
	 * 提取报文 packetId（v3/v5 统一：各 VariableHeader 均继承 MqttMessageIdVariableHeader）
	 * @param msg 待解析的 MQTT 报文
	 * @throws IllegalArgumentException 报文类型不含 messageId（无法解析 packetId）时抛出
	 */
	private int messageId(MqttMessage msg) {
		Object vh = msg.variableHeader();
		if (vh instanceof MqttMessageIdVariableHeader h) {
			return h.messageId();
		}
		throw new IllegalArgumentException("无法解析 packetId: " + msg.fixedHeader().messageType());
	}

	/**
	 * 认证返回码映射：平台 connackCode → Netty MQTT 返回码（v3/v5 共用）。
	 * @param code 平台连接拒绝码（1 协议版本/2 标识符/3 服务不可用/5 未授权/其余 账号或密码错误）
	 * @return 对应 MQTT 连接返回码
	 */
	private MqttConnectReturnCode returnCode(int code) {
		// 平台拒绝码（1 协议版本/2 标识符/3 服务不可用/5 未授权/其余 账号密码错误）→ Netty MQTT 返回码
		return switch (code) {
			case 1 -> MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION;
			case 2 -> MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED;
			case 3 -> MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE;
			case 5 -> MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
			default -> MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
		};
	}

	/**
	 * 从会话 attribute 重建设备凭据（ACL/限速/生命周期通知共用）。
	 *
	 * <p>
	 * 凭据在 CONNECT 阶段写入 session attribute（见 {@link #completeConnect}），此处按需还原； 未写入（理论不可达）返回
	 * null，调用方按无凭据处理。
	 * </p>
	 */
	private DeviceCredential attrCredential(Session session) {
		Object deviceId = session.attr(Session.SessionAttr.DEVICE_ID);
		if (deviceId == null) {
			return null;
		}
		return new DeviceCredential(session.getDeviceKey(), (Long) deviceId,
				(Long) session.attr(Session.SessionAttr.TENANT_ID),
				(String) session.attr(Session.SessionAttr.PRODUCT_KEY),
				(String) session.attr(Session.SessionAttr.DEVICE_NAME),
				(DeviceStatus) session.attr(Session.SessionAttr.DEVICE_STATUS), 1, "", false);
	}

	/**
	 * 提取对端 IP（去端口）；非 InetSocketAddress 返回 null。
	 */
	private String remoteIp(Channel channel) {
		if (channel.remoteAddress() instanceof InetSocketAddress addr) {
			return addr.getAddress().getHostAddress();
		}
		return null;
	}

	/**
	 * CONNECT 解析结果（认证回调使用，跨线程传递）
	 */
	private record ConnectParams(int version, int keepAliveSeconds, boolean cleanSession, Session.MqttWill will,
			String remoteIp, long sessionExpirySeconds, int receiveMaximum, int maxPacketSize) {
	}

}
