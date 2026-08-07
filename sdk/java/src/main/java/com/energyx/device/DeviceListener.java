package com.energyx.device;

/**
 * 设备事件回调。
 *
 * <p>全部回调在 Netty IO 线程触发，实现方不得做阻塞操作（Kafka/DB 写入应丢队列或线程池）；
 * 需要下沉耗时逻辑时自行提交到工作线程。</p>
 */
public interface DeviceListener {

    /** 连接成功（已收到 CONNACK ACCEPTED 并完成 keepalive 调度）。 */
    void onConnected(DeviceIdentity identity);

    /** 收到平台下行指令（down/command）。 */
    void onCommand(DeviceIdentity identity, CommandMessage command);

    /** 连接断开（channelInactive / 主动 close 时不再回调）。 */
    void onDisconnected(DeviceIdentity identity, String reason);

    /** 连接过程异常（握手失败、编解码异常等）。 */
    void onError(DeviceIdentity identity, Throwable cause);
}
