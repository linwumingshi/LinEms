package com.sanduo.energy.command.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.command.service.CommandService;
import com.sanduo.energy.common.kafka.KafkaRecordHandler;
import com.sanduo.energy.common.message.ShadowDeltaMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 影子 delta 消费者：消费 iot-shadow-delta（key=deviceId）→ 物化为 setProperties 指令。
 *
 * <p>setProperties 天然幂等（重复下发同一期望收敛），叠加「同设备在途合并」去重，无需消息级 dedup。</p>
 */
@Slf4j
@Component
public class DeltaCommandConsumer implements KafkaRecordHandler {

    private final ObjectMapper objectMapper;
    private final CommandService commandService;

    public DeltaCommandConsumer(ObjectMapper objectMapper, CommandService commandService) {
        this.objectMapper = objectMapper;
        this.commandService = commandService;
    }

    @Override
    public void handle(ConsumerRecord<String, String> record) throws Exception {
        ShadowDeltaMessage delta = objectMapper.readValue(record.value(), ShadowDeltaMessage.class);
        commandService.materializeDelta(delta);
    }
}
