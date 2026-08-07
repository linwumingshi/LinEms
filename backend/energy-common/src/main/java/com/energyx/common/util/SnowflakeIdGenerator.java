package com.energyx.common.util;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 雪花 ID 生成器。
 * 生产环境 workerId 建议通过 Nacos 配置分配（0~31）；本地默认取本机 MAC 派生，保证多实例不碰撞。
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始时间戳：2026-01-01 00:00:00 */
    private static final long EPOCH = 1767225600000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        long id = this.workerIdFromMac();
        if (id > MAX_WORKER_ID || id < 0) {
            id = 0;
        }
        this.workerId = id;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // 时钟回拨：短暂等待后重试
            long offset = lastTimestamp - timestamp;
            if (offset > 5) {
                throw new IllegalStateException("clock moved backwards, refuse generating id");
            }
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    public String nextIdStr() {
        return String.valueOf(nextId());
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private long workerIdFromMac() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    long hash = 0;
                    for (byte b : mac) {
                        hash = (hash * 31 + (b & 0xFF)) & 0xFFFFFFFFL;
                    }
                    return hash & MAX_WORKER_ID;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        // fallback：本机 IP
        try {
            byte[] ip = InetAddress.getLocalHost().getAddress();
            return ((ip[2] & 0xFF) << 8 | (ip[3] & 0xFF)) & MAX_WORKER_ID;
        } catch (Exception e) {
            return 0;
        }
    }
}
