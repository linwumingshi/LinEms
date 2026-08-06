package com.sanduo.energy.common.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨节点路由信封 JSON 往返 + 载荷编解码测试。
 *
 * <p>该信封是 Broker ↔ access 的跨模块契约，序列化兼容性必须锁定：
 * 任何一端的字段增减都不得破坏另一端反序列化。</p>
 */
class RouterEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishEnvelope_shouldRoundTrip() throws Exception {
        RouterEnvelope env = RouterEnvelope.publish("broker-1", "snd_ess_pcs/dev/up/property",
                "{\"properties\":{\"soc\":85.2}}".getBytes(), 1, false);

        String json = mapper.writeValueAsString(env);
        RouterEnvelope decoded = mapper.readValue(json, RouterEnvelope.class);

        assertEquals(RouterEnvelope.TYPE_PUBLISH, decoded.getType());
        assertEquals("broker-1", decoded.getSourceNode());
        assertEquals("snd_ess_pcs/dev/up/property", decoded.getTopic());
        assertEquals(1, decoded.getQos());
        assertFalse(decoded.isRetain());
        assertArrayEquals("{\"properties\":{\"soc\":85.2}}".getBytes(), decoded.decodePayload());
    }

    @Test
    void kickEnvelope_shouldRoundTrip() throws Exception {
        RouterEnvelope env = RouterEnvelope.kick("broker-2", "snd_ess_pcs_dev");

        RouterEnvelope decoded = mapper.readValue(mapper.writeValueAsString(env), RouterEnvelope.class);

        assertEquals(RouterEnvelope.TYPE_KICK, decoded.getType());
        assertEquals("broker-2", decoded.getSourceNode());
        assertEquals("snd_ess_pcs_dev", decoded.getDeviceKey());
        assertTrue(decoded.getTopic() == null);
    }

    @Test
    void decodePayload_shouldTolerateCorruptBase64() {
        RouterEnvelope env = new RouterEnvelope();
        env.setPayloadBase64("!!!not-base64!!!");
        assertArrayEquals(new byte[0], env.decodePayload());

        RouterEnvelope empty = new RouterEnvelope();
        assertArrayEquals(new byte[0], empty.decodePayload());
    }
}
