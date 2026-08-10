package com.energyx.common.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 跨节点路由信封编解码器（阶段 2 二进制化）。
 *
 * <p>二进制格式（magic=0xE9 0x01，与 JSON（首字节 '{'=0x7B）天然可区分）：
 * <pre>
 * 偏移  长度  字段
 * 0     2    magic 0xE9 0x01
 * 2     1    version = 1
 * 3     1    type（'P'=PUBLISH 'K'=KICK）
 * 4     4    sourceNode 长度（BE）＋ UTF-8
 *       4    topic 长度（BE）＋ UTF-8
 *       4    payload 长度（BE）＋ 原始字节（零 Base64 膨胀）
 *       1    qos
 *       1    retain
 *       4    deviceKey 长度（BE）＋ UTF-8（可空，长度 0xFFFF 表示 null）
 *       8    ts（BE long）
 * </pre></p>
 *
 * <p>兼容策略：decode 自动探测 magic——二进制走快速路径；JSON 走 ObjectMapper
 * （兼容期 mqtt.router 旧通道与测试），平滑滚动升级无需双端同时切换。</p>
 */
public final class RouterEnvelopeCodec {

    private static final byte[] MAGIC = {(byte) 0xE9, 0x01};
    private static final int VERSION = 1;
    private static final byte TYPE_PUBLISH_BYTE = 'P';
    private static final byte TYPE_KICK_BYTE = 'K';
    private static final int NULL_STRING = 0xFFFF;

    private RouterEnvelopeCodec() {
    }

    /** 探测是否为二进制信封（magic 匹配） */
    public static boolean isBinary(byte[] data) {
        return data != null && data.length >= 2
                && data[0] == MAGIC[0] && data[1] == MAGIC[1];
    }

    public static byte[] encode(RouterEnvelope envelope) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.writeBytes(MAGIC);
        out.write(VERSION);
        out.write(RouterEnvelope.TYPE_PUBLISH.equals(envelope.getType()) ? TYPE_PUBLISH_BYTE : TYPE_KICK_BYTE);
        writeString(out, envelope.getSourceNode());
        writeString(out, envelope.getTopic());
        byte[] payload = envelope.decodePayload();
        writeInt(out, payload.length);
        out.writeBytes(payload);
        out.write(envelope.getQos() & 0xFF);
        out.write(envelope.isRetain() ? 1 : 0);
        writeNullableString(out, envelope.getDeviceKey());
        writeLong(out, envelope.getTs() == 0 ? System.currentTimeMillis() : envelope.getTs());
        return out.toByteArray();
    }

    /** 自动探测：二进制走快速路径，JSON 走 Jackson（兼容期） */
    public static RouterEnvelope decode(byte[] data, ObjectMapper objectMapper) {
        if (isBinary(data)) {
            return decodeBinary(data);
        }
        try {
            return objectMapper.readValue(data, RouterEnvelope.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("信封 JSON 反序列化失败", e);
        }
    }

    private static RouterEnvelope decodeBinary(byte[] data) {
        int pos = 2;
        int version = data[pos++] & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("不支持的信封版本: " + version);
        }
        byte type = data[pos++];
        RouterEnvelope env = new RouterEnvelope();
        env.setType(type == TYPE_PUBLISH_BYTE ? RouterEnvelope.TYPE_PUBLISH : RouterEnvelope.TYPE_KICK);
        ReadResult source = readStringAt(data, pos);
        pos += source.totalLen;
        env.setSourceNode(source.value);
        ReadResult topic = readStringAt(data, pos);
        pos += topic.totalLen;
        env.setTopic(topic.value);
        int payloadLen = readInt(data, pos);
        pos += 4;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, pos, payload, 0, payloadLen);
        pos += payloadLen;
        env.setPayloadBase64(Base64.getEncoder().encodeToString(payload));
        env.setQos(data[pos++] & 0xFF);
        env.setRetain(data[pos++] == 1);
        ReadResult deviceKey = readStringAt(data, pos);
        pos += deviceKey.totalLen;
        env.setDeviceKey(deviceKey.nullValue ? null : deviceKey.value);
        env.setTs(readLong(data, pos));
        return env;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        writeInt(out, b.length);
        out.writeBytes(b);
    }

    private static void writeNullableString(ByteArrayOutputStream out, String s) {
        if (s == null) {
            writeInt(out, NULL_STRING);
            return;
        }
        writeString(out, s);
    }

    private static ReadResult readStringAt(byte[] data, int pos) {
        int len = readInt(data, pos);
        if (len == NULL_STRING) {
            return new ReadResult(null, true, 4);
        }
        return new ReadResult(new String(data, pos + 4, len, StandardCharsets.UTF_8), false, 4 + len);
    }

    /** 字符串读取结果：值 + 是否 null + 占用字节数（含长度头） */
    private record ReadResult(String value, boolean nullValue, int totalLen) {
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readInt(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((v >>> (i * 8)) & 0xFF));
        }
    }

    private static long readLong(byte[] data, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[pos + i] & 0xFF);
        }
        return v;
    }
}
