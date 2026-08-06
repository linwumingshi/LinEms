package com.sanduo.device;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectPayload;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.ssl.SslContext;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用最小 MQTT Broker（仅服务 MqttDevice 单测，非生产实现）。
 *
 * <p>能力：</p>
 * <ul>
 *   <li>CONNECT → CONNACK ACCEPTED（记录 clientId/username/password）</li>
 *   <li>SUBSCRIBE → SUBACK</li>
 *   <li>PUBLISH → 记录 topic/payload；QoS1 回 PUBACK</li>
 *   <li>PINGREQ → PINGRESP</li>
 *   <li>DISCONNECT → 记录并关闭</li>
 *   <li>{@link #publishDown} 向指定 clientId 注入下行报文</li>
 * </ul>
 */
final class MqttTestBroker implements AutoCloseable {

    record RecordedPublish(String clientId, String topic, byte[] payload) {
    }

    /** password 与真实 Broker 相同解码：UTF-8 字符串（应为 HMAC 十六进制签名）。 */
    record Connection(String clientId, String username, String password) {
    }

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
    private final Channel serverChannel;
    private final int port;

    private final List<RecordedPublish> publishes = new CopyOnWriteArrayList<>();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final List<String> pings = new CopyOnWriteArrayList<>();
    private final List<String> disconnects = new CopyOnWriteArrayList<>();
    private final Map<String, Channel> clients = new ConcurrentHashMap<>();
    /** true 时对下一个 CONNECT 回 CONNACK_REFUSED_NOT_AUTHORIZED（测拒绝分支）。 */
    private volatile boolean rejectConnects;
    /** true 时对 CONNECT 不回 CONNACK（测超时分支）。 */
    private volatile boolean silenceConnects;

    MqttTestBroker() throws InterruptedException {
        this(null);
    }

    /** TLS 变体：serverSslCtx 非 null 时 pipeline 头部加 SslHandler（先解密再走 MQTT 编解码）。 */
    MqttTestBroker(SslContext serverSslCtx) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (serverSslCtx != null) {
                            ch.pipeline().addLast("ssl", serverSslCtx.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast("decoder", new MqttDecoder(256 * 1024));
                        ch.pipeline().addLast("encoder", MqttEncoder.INSTANCE);
                        ch.pipeline().addLast("broker", new BrokerHandler());
                    }
                });
        this.serverChannel = bootstrap.bind(0).sync().channel();
        this.port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    int port() {
        return port;
    }

    List<RecordedPublish> publishes() {
        return publishes;
    }

    List<Connection> connections() {
        return connections;
    }

    List<String> pings() {
        return pings;
    }

    List<String> disconnects() {
        return disconnects;
    }

    boolean hasClient(String clientId) {
        return clients.containsKey(clientId);
    }

    void rejectNextConnect(boolean v) {
        this.rejectConnects = v;
    }

    void silenceNextConnect(boolean v) {
        this.silenceConnects = v;
    }

    /** 强制断开指定客户端（模拟服务端踢线 / 节点重启接管）。 */
    void kick(String clientId) {
        Channel ch = clients.get(clientId);
        if (ch == null) {
            throw new IllegalStateException("client 未连接: " + clientId);
        }
        ch.close();
    }

    /** 向指定客户端注入下行报文（模拟平台下发指令）。 */
    void publishDown(String clientId, String topic, byte[] payload) {
        Channel ch = clients.get(clientId);
        if (ch == null) {
            throw new IllegalStateException("client 未连接: " + clientId);
        }
        MqttPublishMessage msg = new MqttPublishMessage(
                new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                new MqttPublishVariableHeader(topic, 1),
                Unpooled.wrappedBuffer(payload));
        ch.writeAndFlush(msg);
    }

    @Override
    public void close() {
        serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private final class BrokerHandler extends SimpleChannelInboundHandler<MqttMessage> {

        private String clientId;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
            if (msg instanceof MqttConnectMessage connect) {
                clientId = connect.payload().clientIdentifier();
                MqttConnectPayload payload = connect.payload();
                String password = payload.passwordInBytes() == null
                        ? "" : new String(payload.passwordInBytes(), java.nio.charset.StandardCharsets.UTF_8);
                connections.add(new Connection(clientId, payload.userName(), password));
                clients.put(clientId, ctx.channel());
                if (rejectConnects) {
                    rejectConnects = false;
                    ctx.writeAndFlush(new MqttConnAckMessage(
                            new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 2),
                            new MqttConnAckVariableHeader(
                                    MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED, false)));
                    return;
                }
                if (silenceConnects) {
                    silenceConnects = false;
                    return;
                }
                ctx.writeAndFlush(new MqttConnAckMessage(
                        new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 2),
                        new MqttConnAckVariableHeader(MqttConnectReturnCode.CONNECTION_ACCEPTED, false)));
            } else if (msg instanceof MqttSubscribeMessage sub) {
                int packetId = sub.variableHeader().messageId();
                ctx.writeAndFlush(new MqttSubAckMessage(
                        new MqttFixedHeader(MqttMessageType.SUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                        MqttMessageIdVariableHeader.from(packetId),
                        new MqttSubAckPayload(0)));
            } else if (msg instanceof MqttPublishMessage pub) {
                String topic = pub.variableHeader().topicName();
                int packetId = pub.variableHeader().packetId();
                byte[] payload = new byte[pub.payload().readableBytes()];
                pub.payload().readBytes(payload);
                publishes.add(new RecordedPublish(clientId, topic, payload));
                if (packetId > 0) {
                    ctx.writeAndFlush(new MqttPubAckMessage(
                            new MqttFixedHeader(MqttMessageType.PUBACK, false, MqttQoS.AT_MOST_ONCE, false, 2),
                            MqttMessageIdVariableHeader.from(packetId)));
                }
            } else if (msg.fixedHeader() != null && msg.fixedHeader().messageType() == MqttMessageType.PINGREQ) {
                pings.add(clientId);
                ctx.writeAndFlush(new MqttMessage(
                        new MqttFixedHeader(MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0)));
            } else if (msg.fixedHeader() != null && msg.fixedHeader().messageType() == MqttMessageType.DISCONNECT) {
                disconnects.add(clientId);
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            clients.remove(clientId);
        }
    }
}
