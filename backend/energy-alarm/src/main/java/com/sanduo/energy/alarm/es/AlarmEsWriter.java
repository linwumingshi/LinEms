package com.sanduo.energy.alarm.es;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.alarm.config.AlarmProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警日志 ES 直写（JDK HttpClient，按日索引 es-alarm-log-{yyyyMM}）。
 *
 * <p>写入为<b>尽力而为</b>：单线程异步队列 + 失败记日志，不阻塞告警主链路（告警权威源是 MySQL
 * iot_alarm_record，ES 仅作检索/大屏日志冗余）。索引/字段对齐 sql/elasticsearch/alarm_log.mapping.json。</p>
 */
@Slf4j
@Component
public class AlarmEsWriter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final AlarmProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final ExecutorService executor;

    public AlarmEsWriter(AlarmProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "alarm-es-writer-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** 异步写一条告警文档（开关关闭或抛异常均不外溢） */
    public void writeAsync(String alarmEventId, Map<String, Object> doc) {
        if (!props.isEsEnabled()) {
            return;
        }
        executor.submit(() -> write(alarmEventId, doc));
    }

    private void write(String alarmEventId, Map<String, Object> doc) {
        String index = "es-alarm-log-" + LocalDate.now().format(MONTH);
        try {
            String url = props.getEsUrl().replaceAll("/+$", "") + "/" + index + "/_doc/" + alarmEventId;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(doc), StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("[Alarm] ES 写入失败 index={} id={} status={} resp={}",
                        index, alarmEventId, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("[Alarm] ES 写入异常 index={} id={}", index, alarmEventId, e);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }
}
