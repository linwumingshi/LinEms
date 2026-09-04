package com.energyx.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectPayload;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttConnectVariableHeader;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EnergyX 设备端 MQTT 客户端（Netty 实现，零第三方 MQTT 依赖，与自研 Broker 同族 codec）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>HMAC 认证 CONNECT（username=clientId&amp;ts&amp;nonce，password=HMAC 签名），同步等待 CONNACK；</li>
 *   <li>连接成功后订阅 {@code {pk}/{dn}/down/command} 下行指令；</li>
 *   <li>QoS0 上行属性/事件/生命周期、QoS1 上行指令 ACK；</li>
 *   <li>keepalive PINGREQ 周期保活；</li>
 *   <li>收到指令后自动回 ack（可关闭）；</li>
 *   <li>优雅断开（DISCONNECT + 关闭通道）。</li>
 * </ul>
 *
 * <p>线程安全：connect/close/publish 可由任意线程调用；回调在 Netty IO 线程触发。</p>
 */
public class MqttDevice implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttDevice.class);

    private static final MqttFixedHeader PINGREQ =
            new MqttFixedHeader(MqttMessageType.PINGREQ, false, MqttQoS.AT_MOST_ONCE, false, 0);
    private static final MqttFixedHeader DISCONNECT =
            new MqttFixedHeader(MqttMessageType.DISCONNECT, false, MqttQoS.AT_MOST_ONCE, false, 0);

    private final DeviceIdentity identity;
    private final MqttClientConfig config;
    private final DeviceListener listener;
    private final EventLoopGroup eventLoopGroup;
    private final boolean ownsEventLoop;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger packetIdSeq = new AtomicInteger(1);

    private volatile Channel channel;
    private volatile ScheduledFuture<?> keepAliveTask;
    private volatile SslContext clientSslContext;
    private volatile CompletableFuture<MqttConnAckMessage> connAckFuture = new CompletableFuture<>();
    private volatile boolean connected;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.concurrent.ScheduledExecutorService reconnectScheduler;

    public MqttDevice(DeviceIdentity identity, MqttClientConfig config, DeviceListener listener) {
        this(identity, config, listener, null);
    }

    /**
     * @param sharedGroup 共享 EventLoopGroup（压测场景多设备共用，减少线程资源）；
     *                    传 null 时客户端自建单线程 NioEventLoopGroup
     */
    public MqttDevice(DeviceIdentity identity, MqttClientConfig config,
                      DeviceListener listener, EventLoopGroup sharedGroup) {
        this.identity = identity;
        this.config = config == null ? MqttClientConfig.defaults() : config;
        this.listener = listener;
        this.ownsEventLoop = sharedGroup == null;
        this.eventLoopGroup = sharedGroup == null ? new NioEventLoopGroup(1) : sharedGroup;
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /**
     * 懒创建客户端 TLS 上下文（重连复用）：skipVerify → 信任全部；否则固定信任 tlsTrustCertFile；
     * 两者都无 → JDK 默认信任库（自签名会被拒）。
     */
    private SslContext clientSslContext() throws SSLException {
        SslContext ctx = clientSslContext;
        if (ctx == null) {
            synchronized (this) {
                ctx = clientSslContext;
                if (ctx == null) {
                    SslContextBuilder builder = SslContextBuilder.forClient();
                    if (config.tlsSkipVerify()) {
                        builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                    } else if (config.tlsTrustCertFile() != null && !config.tlsTrustCertFile().isBlank()) {
                        builder.trustManager(new File(config.tlsTrustCertFile()));
                    }
                    ctx = builder.build();
                    clientSslContext = ctx;
                }
            }
        }
        return ctx;
    }

    /**
     * 建立连接并完成认证。阻塞直到收到 CONNACK（超时上限见 {@code connectTimeoutMs}）。
     *
     * @throws IllegalStateException 连接被拒绝 / CONNACK 超时 / TCP 失败
     */
    public void connect() {
        if (closed) {
            throw new IllegalStateException("设备已关闭，不可重复连接: " + identity.clientId());
        }
        connAckFuture = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        if (config.useTls()) {
                            // SslHandler 必须位于 pipeline 头部：TLS 解密后才到 MQTT 编解码
                            SslHandler sslHandler = clientSslContext().newHandler(
                                    ch.alloc(), config.host(), config.port()); // peerHost 供主机名校验/SNI
                            if (!config.tlsSkipVerify()) {
                                SSLEngine engine = sslHandler.engine();
                                SSLParameters params = engine.getSSLParameters();
                                params.setEndpointIdentificationAlgorithm("HTTPS"); // 校验证书 SAN 主机名/IP
                                engine.setSSLParameters(params);
                            }
                            ch.pipeline().addLast("ssl", sslHandler);
                        }
                        ch.pipeline().addLast("mqtt-encoder", MqttEncoder.INSTANCE);
                        ch.pipeline().addLast("mqtt-decoder", new MqttDecoder(256 * 1024));
                        ch.pipeline().addLast("device-handler", new DeviceChannelHandler());
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(config.host(), config.port());
        connectFuture.syncUninterruptibly();
        if (!connectFuture.isSuccess()) {
            throw new IllegalStateException("TCP 连接失败 host=" + config.host() + " port=" + config.port()
                    + " clientId=" + identity.clientId(), connectFuture.cause());
        }
        this.channel = connectFuture.channel();
        writeConnect();

        MqttConnAckMessage ack;
        try {
            ack = connAckFuture.get(config.connectTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            doClose();
            throw new IllegalStateException("CONNACK 超时（" + config.connectTimeoutMs() + "ms）: "
                    + identity.clientId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            doClose();
            throw new IllegalStateException("CONNACK 等待被中断: " + identity.clientId(), e);
        } catch (ExecutionException e) {
            doClose();
            // TLS 握手异常可能被 MqttDecoder 包一层 DecoderException，沿 cause 链探测
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof SSLHandshakeException) {
                    throw new IllegalStateException("TLS 握手失败 host=" + config.host() + " port=" + config.port()
                            + "（自签名需 tlsSkipVerify 或 tlsTrustCertFile）: " + identity.clientId(), t);
                }
            }
            throw new IllegalStateException("连接被服务端拒绝: " + identity.clientId(), e.getCause());
        }

        MqttConnectReturnCode code = ack.variableHeader().connectReturnCode();
        if (code != MqttConnectReturnCode.CONNECTION_ACCEPTED) {
            doClose();
            throw new IllegalStateException("连接被拒绝 code=" + code + " clientId=" + identity.clientId());
        }

        connected = true;
        scheduleKeepAlive();
        if (config.subscribeCommand()) {
            subscribeDownCommand();
        }
        listener.onConnected(identity);
    }

    private void writeConnect() {
        long ts = System.currentTimeMillis();
        String nonce = HmacAuth.randomNonce();
        String clientId = identity.clientId();
        String username = HmacAuth.buildUsername(clientId, ts, nonce);
        String password = HmacAuth.sign(identity.deviceSecret(), clientId, Long.toString(ts), nonce);

        MqttConnectVariableHeader variableHeader = new MqttConnectVariableHeader(
                "MQTT", 4, true, true, false, 0, false, true, config.keepAliveSeconds());
        MqttConnectPayload payload = new MqttConnectPayload(
                clientId, null, null, null, username, password.getBytes(StandardCharsets.UTF_8));
        channel.writeAndFlush(new MqttConnectMessage(
                new MqttFixedHeader(MqttMessageType.CONNECT, false, MqttQoS.AT_MOST_ONCE, false, 0),
                variableHeader, payload));
    }

    private void subscribeDownCommand() {
        String topic = identity.downCommandTopic();
        MqttFixedHeader fixed = new MqttFixedHeader(MqttMessageType.SUBSCRIBE, false,
                MqttQoS.AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader variable = MqttMessageIdVariableHeader.from(nextPacketId());
        MqttSubscribePayload payload = new MqttSubscribePayload(
                List.of(new MqttTopicSubscription(topic, MqttQoS.AT_MOST_ONCE)));
        channel.writeAndFlush(new MqttSubscribeMessage(fixed, variable, payload));
        log.debug("[SDK] {} 已订阅下行指令 {}", identity.clientId(), topic);
    }

    private void scheduleKeepAlive() {
        Channel ch = channel;
        if (ch == null) {
            return;
        }
        int interval = Math.max(1, config.keepAliveSeconds());
        keepAliveTask = ch.eventLoop().scheduleAtFixedRate(() -> {
            if (ch.isActive()) {
                ch.writeAndFlush(new MqttMessage(PINGREQ));
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // 上行
    // ------------------------------------------------------------------

    /** 上报属性（QoS0）。payload = {messageId, dataType, properties, ts}。 */
    public void publishProperty(Map<String, Object> properties) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("messageId", identity.clientId() + "_" + nextPacketId());
        root.put("dataType", "report");
        root.put("properties", properties == null ? Map.of() : properties);
        root.put("ts", System.currentTimeMillis());
        publish(identity.propertyTopic(), json.writeValueAsBytes(root), MqttQoS.AT_MOST_ONCE);
    }

    /** 上报事件（QoS0）。payload = {messageId, eventName, severity, code, data, ts}。 */
    public void publishEvent(String eventName, int severity, String code,
                             Map<String, Object> data) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("messageId", identity.clientId() + "_" + nextPacketId());
        root.put("eventName", eventName);
        root.put("severity", severity);
        if (code != null) {
            root.put("code", code);
        }
        root.put("data", data == null ? Map.of() : data);
        root.put("ts", System.currentTimeMillis());
        publish(identity.eventTopic(), json.writeValueAsBytes(root), MqttQoS.AT_MOST_ONCE);
    }

    /** 自报上下线（QoS0）。payload = {messageId, eventType, ip, ts}。 */
    public void publishLifecycle(String eventType, String ip) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("messageId", identity.clientId() + "_" + nextPacketId());
        root.put("eventType", eventType);
        if (ip != null) {
            root.put("ip", ip);
        }
        root.put("ts", System.currentTimeMillis());
        publish(identity.lifecycleTopic(), json.writeValueAsBytes(root), MqttQoS.AT_MOST_ONCE);
    }

    /**
     * 回指令 ACK（QoS1，保证不丢）。payload = {commandId, status, errorCode, result, ts}。
     *
     * @param commandId 指令 ID
     * @param status    SUCCESS / FAILED
     * @param errorCode 失败错误码（成功传 null）
     * @param result    成功结果（可 null）
     */
    public void ackCommand(String commandId, String status, String errorCode,
                           Map<String, Object> result) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("commandId", commandId);
        root.put("status", status);
        if (errorCode != null) {
            root.put("errorCode", errorCode);
        }
        root.put("result", result == null ? Map.of() : result);
        root.put("ts", System.currentTimeMillis());
        try {
            publish(identity.ackTopic(), json.writeValueAsBytes(root), MqttQoS.AT_LEAST_ONCE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ACK 序列化失败 commandId=" + commandId, e);
        }
    }

    /** 根据收到的下行指令自动回 ack（成功或失败由 config.ackErrorCode 决定）。 */
    public void ackCommand(CommandMessage command) {
        String status = config.ackErrorCode() == null ? "SUCCESS" : "FAILED";
        ackCommand(command.commandId(), status, config.ackErrorCode(),
                status.equals("SUCCESS") ? Map.of("exec", "ok") : null);
    }

    /** 通用发布（指定 QoS）。 */
    public void publish(String topic, byte[] payload, MqttQoS qos) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            throw new IllegalStateException("MQTT 未连接或已断开: " + identity.clientId());
        }
        if (qos == MqttQoS.AT_LEAST_ONCE) {
            MqttPublishMessage msg = new MqttPublishMessage(
                    new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                    new MqttPublishVariableHeader(topic, nextPacketId()),
                    Unpooled.wrappedBuffer(payload));
            ch.writeAndFlush(msg);
        } else {
            MqttPublishMessage msg = new MqttPublishMessage(
                    new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_MOST_ONCE, false, 0),
                    new MqttPublishVariableHeader(topic, 0),
                    Unpooled.wrappedBuffer(payload));
            ch.writeAndFlush(msg);
        }
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    public boolean isConnected() {
        Channel ch = channel;
        return connected && ch != null && ch.isActive();
    }

    public DeviceIdentity identity() {
        return identity;
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    /** 优雅断开：发 DISCONNECT 后关闭通道；释放自建 EventLoopGroup / 重连调度器。 */
    @Override
    public void close() {
        closed = true;
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
        java.util.concurrent.ScheduledExecutorService scheduler = reconnectScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
            reconnectScheduler = null;
        }
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            try {
                ch.writeAndFlush(new MqttMessage(DISCONNECT)).syncUninterruptibly();
            } catch (Exception ignore) {
                // 发送失败也继续关闭
            }
        }
        doClose();
    }

    private void doClose() {
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        if (ownsEventLoop) {
            eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // 重连（指数退避，独立单线程调度器，避免阻塞共享 EventLoopGroup）
    // ------------------------------------------------------------------

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

    private void scheduleReconnect(int attempt) {
        if (closed || !config.autoReconnect()) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        if (reconnectScheduler == null) {
            synchronized (this) {
                if (reconnectScheduler == null) {
                    reconnectScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "sdk-reconnect-" + identity.clientId());
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        long delay = MqttDevice.reconnectDelayMillis((long) config.reconnectBackoffMs(),
                (long) config.reconnectMaxBackoffMs(), attempt);
        reconnectScheduler.schedule(() -> doReconnect(attempt), delay, TimeUnit.MILLISECONDS);
    }

    private void doReconnect(int attempt) {
        reconnectScheduled.set(false);
        if (closed) {
            return;
        }
        try {
            connect();
        } catch (Exception e) {
            log.warn("[SDK] 重连失败 clientId={} 第{}次: {}", identity.clientId(), attempt + 1, e.getMessage());
            scheduleReconnect(attempt + 1);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private int nextPacketId() {
        return packetIdSeq.getAndUpdate(v -> v >= 65535 ? 1 : v + 1);
    }

    /** 通道事件处理器（IO 线程）。 */
    private final class DeviceChannelHandler extends SimpleChannelInboundHandler<MqttMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
            if (msg instanceof MqttConnAckMessage ack) {
                connAckFuture.complete(ack);
            } else if (msg instanceof MqttPublishMessage pub) {
                handleDownCommand(pub);
            }
            // SUBACK / PUBACK / PINGRESP：SDK 无需处理
        }

        private void handleDownCommand(MqttPublishMessage pub) {
            String topic = pub.variableHeader().topicName();
            byte[] payload = new byte[pub.payload().readableBytes()];
            pub.payload().readBytes(payload);
            if (!identity.downCommandTopic().equals(topic)) {
                return;
            }
            try {
                Map<String, Object> raw = json.readValue(payload, JsonMapReader.TYPE);
                CommandMessage command = CommandMessage.fromMap(raw);
                listener.onCommand(identity, command);
                if (config.autoAck()) {
                    ackCommand(command);
                }
            } catch (Exception e) {
                log.warn("[SDK] 下行指令解析失败 clientId={} topic={}", identity.clientId(), topic, e);
                listener.onError(identity, e);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            boolean wasConnected = connected;
            connected = false;
            if (keepAliveTask != null) {
                keepAliveTask.cancel(false);
                keepAliveTask = null;
            }
            // 握手阶段断开 → 让 connect() 立刻失败，避免干等超时
            connAckFuture.completeExceptionally(new IllegalStateException("连接在 CONNACK 前被关闭"));
            if (wasConnected && !closed) {
                listener.onDisconnected(identity, "channel-inactive");
                scheduleReconnect(0);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!connAckFuture.isDone()) {
                // 握手阶段（TLS 失败等）以真实原因失败 connect()，而非笼统「CONNACK 前被关闭」
                connAckFuture.completeExceptionally(cause);
                ctx.close();
            } else {
                log.warn("[SDK] 通道异常 clientId={}", identity.clientId(), cause);
                listener.onError(identity, cause);
            }
        }
    }

    /** JSON 反序列化类型引用（避免反复创建 TypeReference）。 */
    private static final class JsonMapReader {
        private static final com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>> TYPE =
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                };

        private JsonMapReader() {
        }
    }
}
