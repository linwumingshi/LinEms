package com.energyx.common.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 二进制信封编解码（阶段 2）：往返一致性 + 二进制/JSON 自动探测 + KICK。
 */
class RouterEnvelopeCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishRoundTrip() {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 & 0xFF); // 含 0x00/0xE9/0x01 等任意字节
        }
        RouterEnvelope env = RouterEnvelope.publish("broker-1", "pk1/dev1/up/property",
                payload, 1, true);
        byte[] encoded = RouterEnvelopeCodec.encode(env);
        assertTrue(RouterEnvelopeCodec.isBinary(encoded), "magic 应识别为二进制");

        RouterEnvelope decoded = RouterEnvelopeCodec.decode(encoded, objectMapper);
        assertEquals(RouterEnvelope.TYPE_PUBLISH, decoded.getType());
        assertEquals("broker-1", decoded.getSourceNode());
        assertEquals("pk1/dev1/up/property", decoded.getTopic());
        assertArrayEquals(payload, decoded.decodePayload(), "二进制 payload 零膨胀无损");
        assertEquals(1, decoded.getQos());
        assertTrue(decoded.isRetain());
        assertEquals(env.getTs(), decoded.getTs());
    }

    @Test
    void kickRoundTrip() {
        RouterEnvelope env = RouterEnvelope.kick("broker-2", "pk1_dev9");
        RouterEnvelope decoded = RouterEnvelopeCodec.decode(RouterEnvelopeCodec.encode(env), objectMapper);
        assertEquals(RouterEnvelope.TYPE_KICK, decoded.getType());
        assertEquals("pk1_dev9", decoded.getDeviceKey());
        assertEquals("broker-2", decoded.getSourceNode());
        assertTrue(decoded.decodePayload().length == 0, "KICK 无 payload");
    }

    @Test
    void emptyAndUnicodeStrings() {
        RouterEnvelope env = RouterEnvelope.publish("node-中文-节点", "产品A/设备名/up/property",
                "储能柜温度=25.6℃".getBytes(StandardCharsets.UTF_8), 0, false);
        env.setDeviceKey("产品A_设备名");
        RouterEnvelope decoded = RouterEnvelopeCodec.decode(RouterEnvelopeCodec.encode(env), objectMapper);
        assertEquals("node-中文-节点", decoded.getSourceNode());
        assertEquals("产品A/设备名/up/property", decoded.getTopic());
        assertEquals("产品A_设备名", decoded.getDeviceKey());
        assertEquals("储能柜温度=25.6℃", decoded.decodePayloadAsText());
    }

    @Test
    void jsonCompatibilityAutoDetect() throws Exception {
        // 兼容期：JSON 信封（旧 mqtt.router 通道）仍可自动解码
        RouterEnvelope env = RouterEnvelope.publish("broker-1", "pk/dn/up/event",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8), 1, false);
        byte[] json = objectMapper.writeValueAsBytes(env);
        assertFalse(RouterEnvelopeCodec.isBinary(json), "JSON 不应命中 magic");
        RouterEnvelope decoded = RouterEnvelopeCodec.decode(json, objectMapper);
        assertEquals(RouterEnvelope.TYPE_PUBLISH, decoded.getType());
        assertEquals("{\"a\":1}", decoded.decodePayloadAsText());
    }

    @Test
    void truncatedBinaryFailsFast() {
        RouterEnvelope env = RouterEnvelope.publish("broker-1", "a/b/up/c", new byte[]{1, 2, 3}, 1, false);
        byte[] encoded = RouterEnvelopeCodec.encode(env);
        byte[] truncated = new byte[encoded.length - 3];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        assertThrows(Exception.class, () -> RouterEnvelopeCodec.decode(truncated, objectMapper),
                "截断报文必须快速失败而非静默越界");
    }
}
