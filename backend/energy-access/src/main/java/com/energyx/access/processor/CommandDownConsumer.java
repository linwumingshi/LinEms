package com.energyx.access.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.access.publish.EventPublisher;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.CommandDownMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 下行指令消费（iot-command-down，key=deviceId）：桥接为 mqtt.router PUBLISH 信封下发。
 *
 * <p>Phase 6 Command Center 产出指令 → 本服务桥接 → Broker 全节点 fan-out → 设备所在节点
 * 按 QoS1 投递 down/command。离线补发：设备上线时 OfflineCommandRedeliverer 把 Redis
 * 离线队列（iot:cmd:q）中的 CommandDownMessage 重新桥接，路径复用本方法。</p>
 */
@Slf4j
@Component
public class CommandDownConsumer implements KafkaRecordHandler {

    private final EventPublisher publisher;
    private final ObjectMapper objectMapper;

    public CommandDownConsumer(EventPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(ConsumerRecord<String, String> record) {
        CommandDownMessage cmd;
        try {
            cmd = objectMapper.readValue(record.value(), CommandDownMessage.class);
        } catch (Exception e) {
            log.warn("[Access] command-down 反序列化失败 key={} offset={}，进 DLQ",
                    record.key(), record.offset());
            throw new IllegalArgumentException("command-down 反序列化失败", e);
        }
        publisher.publishRouterDown(cmd);
    }
}
