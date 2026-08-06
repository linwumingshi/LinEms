package com.sanduo.energy.tsdb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * energy-tsdb 配置（sanduo.tsdb.*）。
 */
@Data
@ConfigurationProperties(prefix = "sanduo.tsdb")
public class TsdbProperties {

    /** 实例标识（多实例时区分） */
    private String nodeId = "tsdb-1";

    private String kafkaBootstrapServers = "127.0.0.1:9092";

    /** 每组消费线程数（属性/事件各一组） */
    private int consumerThreads = 4;

    /** Kafka poll 间隔（ms） */
    private long pollMs = 200;

    /** 属性宽表库 */
    private String rawDb = "iot_tsdb_raw";

    /** 事件库 */
    private String eventDb = "iot_tsdb_event";

    /** TAOS-RS JDBC URL（默认库随连接指定） */
    private String jdbcUrl = "jdbc:TAOS-RS://127.0.0.1:6041/iot_tsdb_raw";

    private String jdbcUsername = "root";

    /** P0-4 起无默认值：由 Nacos 配置 energy-shared.yaml 注入（sanduo.tsdb.jdbc-password）。 */
    private String jdbcPassword;

    /** 批量阈值：行数 */
    private int batchSize = 1000;

    /** 批量阈值：估算字节数 */
    private long batchBytes = 2L * 1024 * 1024;

    /** 消费边界幂等窗口（秒） */
    private long msgDedupTtlSeconds = 300;

    /** 死信 topic */
    private String dlqTopic = "iot-dlq";

    /** 子表前缀（dev_{deviceId}） */
    private String tablePrefix = "dev_";

    /** 事件子表后缀（dev_{deviceId}_evt） */
    private String eventTableSuffix = "_evt";
}
