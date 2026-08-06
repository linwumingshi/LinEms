package com.sanduo.energy.alarm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.alarm.config.AlarmProperties;
import com.sanduo.energy.alarm.engine.AlarmRuleEngine;
import com.sanduo.energy.alarm.es.AlarmEsWriter;
import com.sanduo.energy.alarm.mapper.AlarmRecordMapper;
import com.sanduo.energy.alarm.mapper.AlarmRuleMapper;
import com.sanduo.energy.alarm.mapper.ProductInfoMapper;
import com.sanduo.energy.alarm.model.AlarmCondition;
import com.sanduo.energy.alarm.model.AlarmRecordRow;
import com.sanduo.energy.alarm.model.AlarmRuleRow;
import com.sanduo.energy.alarm.util.AlarmRedisKeys;
import com.sanduo.energy.alarm.web.dto.AlarmRecordView;
import com.sanduo.energy.alarm.ws.AlarmWebSocketHandler;
import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.message.AlarmMessage;
import com.sanduo.energy.common.message.ThingEventMessage;
import com.sanduo.energy.common.message.ThingPropertyMessage;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.util.SnowflakeIdGenerator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警中心核心服务。
 *
 * <p><b>规则引擎</b>：启用规则全量缓存（启动加载 + @Scheduled 刷新），每条消息按
 * 触发类型（属性 1 / 事件 2）与作用域（全局 / 产品 / 设备）匹配规则。</p>
 *
 * <p><b>属性规则三阶段</b>：
 * <ol>
 *   <li><b>持续窗口</b>（Redis {@code alarm:sustain:{ruleId}:{deviceId}}）：首次违反记时间戳，
 *       连续超阈满 windowSec 才进入触发判定，抑制毛刺/瞬时抖动；</li>
 *   <li><b>静默防抖</b>（Redis {@code alarm:silence:{ruleId}:{deviceId}}）：触发后 SETNX 置静默，
 *       静默期内同规则不重复告警，合并为一条事件流；</li>
 *   <li><b>恢复</b>：属性回正常区间（显式 recovery 条件命中，或无 recovery 时条件不满足）→
 *       触发中记录批量置已恢复 + 发 RECOVERED 广播。</li>
 * </ol>
 * </p>
 *
 * <p><b>事件规则</b>：事件标识精确匹配 + 静默防抖，级别取事件携带 severity（缺省用规则 severity）。</p>
 *
 * <p><b>幂等性</b>：alarm_event_id 雪花主键天然唯一；静默 SETNX 原子防抖；
 * 恢复/确认用条件更新（WHERE status=0 / !=2）重复请求空操作——Kafka 重放不产生重复记录。</p>
 */
@Slf4j
@Service
public class AlarmService {

    /** 触发类型 */
    private static final int TRIGGER_PROPERTY = 1;
    private static final int TRIGGER_EVENT = 2;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AlarmRuleMapper ruleMapper;
    private final AlarmRecordMapper recordMapper;
    private final ProductInfoMapper productMapper;
    private final StringRedisTemplate redis;
    private final AlarmProperties props;
    private final AlarmKafkaPublisher publisher;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    /** 启用规则缓存（volatile 保证刷新可见性） */
    private volatile List<AlarmRuleRow> ruleCache = List.of();

    /** product_key → product_id 本地缓存（带 TTL） */
    private final ConcurrentHashMap<String, ProductCacheEntry> productCache = new ConcurrentHashMap<>();

    public AlarmService(AlarmRuleMapper ruleMapper, AlarmRecordMapper recordMapper,
                        ProductInfoMapper productMapper, StringRedisTemplate redis,
                        AlarmProperties props, AlarmKafkaPublisher publisher,
                        SnowflakeIdGenerator idGenerator, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.productMapper = productMapper;
        this.redis = redis;
        this.props = props;
        this.publisher = publisher;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        reloadRules();
    }

    /** 规则缓存定时刷新 */
    @Scheduled(fixedDelayString = "${sanduo.alarm.rule-cache-refresh-ms:30000}",
            initialDelayString = "${sanduo.alarm.rule-cache-initial-delay-ms:10000}")
    public void refreshRules() {
        reloadRules();
    }

    private void reloadRules() {
        try {
            List<AlarmRuleRow> fresh = ruleMapper.selectEnabledRules();
            ruleCache = fresh == null ? List.of() : List.copyOf(fresh);
            log.info("[Alarm] 规则缓存刷新 count={}", ruleCache.size());
        } catch (Exception e) {
            // MySQL 不可用不阻塞启动/消费，等待下次刷新重试
            log.error("[Alarm] 规则缓存加载失败（等待下次刷新）", e);
        }
    }

    // ------------------------------------------------------------------
    // 消费端
    // ------------------------------------------------------------------

    /** 属性上报 → 属性规则检测（iot-thing-property） */
    public void handlePropertyReport(ThingPropertyMessage msg) {
        if (msg.getDeviceId() == null || msg.getProperties() == null || msg.getProperties().isEmpty()) {
            return;
        }
        Long productId = resolveProductId(msg.getProductKey());
        for (AlarmRuleRow rule : ruleCache) {
            try {
                if (!matchesRule(rule, msg.getTenantId(), msg.getDeviceId(), productId)) {
                    continue;
                }
                if (rule.getTriggerType() != null && rule.getTriggerType() != TRIGGER_PROPERTY) {
                    continue;
                }
                handlePropertyRule(rule, msg);
            } catch (Exception e) {
                log.error("[Alarm] 属性规则处理失败 ruleId={} deviceId={}", rule.getRuleId(), msg.getDeviceId(), e);
            }
        }
    }

    /** 事件上报 → 事件规则检测（iot-thing-event） */
    public void handleEventReport(ThingEventMessage msg) {
        if (msg.getDeviceId() == null || msg.getEventName() == null) {
            return;
        }
        Long productId = resolveProductId(msg.getProductKey());
        for (AlarmRuleRow rule : ruleCache) {
            try {
                if (!matchesRule(rule, msg.getTenantId(), msg.getDeviceId(), productId)) {
                    continue;
                }
                if (rule.getTriggerType() == null || rule.getTriggerType() != TRIGGER_EVENT) {
                    continue;
                }
                AlarmCondition condition = parseCondition(rule.getCondition());
                if (!AlarmRuleEngine.eventMet(condition, msg.getEventName())) {
                    continue;
                }
                fireEvent(rule, msg);
            } catch (Exception e) {
                log.error("[Alarm] 事件规则处理失败 ruleId={} deviceId={}", rule.getRuleId(), msg.getDeviceId(), e);
            }
        }
    }

    private void handlePropertyRule(AlarmRuleRow rule, ThingPropertyMessage msg) {
        AlarmCondition condition = parseCondition(rule.getCondition());
        if (condition.getMetric() == null) {
            log.warn("[Alarm] 属性规则缺 metric，跳过 ruleId={}", rule.getRuleId());
            return;
        }
        Object value = msg.getProperties().get(condition.getMetric());
        if (value == null) {
            return; // 本次上报不含监控属性
        }
        if (AlarmRuleEngine.propertyMet(condition, value)) {
            if (!isSustained(rule, msg.getDeviceId(), condition.getWindowSec())) {
                return; // 未达持续窗口，不算告警
            }
            fireProperty(rule, msg, condition, value);
        } else {
            resetSustain(rule, msg.getDeviceId());
            tryRecover(rule, msg, value, condition);
        }
    }

    private void fireProperty(AlarmRuleRow rule, ThingPropertyMessage msg,
                              AlarmCondition condition, Object value) {
        if (isSilenced(rule.getRuleId(), msg.getDeviceId())) {
            return;
        }
        int level = rule.getSeverity() == null ? 3 : rule.getSeverity();
        String message = rule.getRuleName() + "：" + condition.getMetric() + " " + condition.getOp()
                + " " + condition.getValue() + "（当前 " + value + "）";
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("metric", condition.getMetric());
        ext.put("currentValue", value);
        ext.put("threshold", condition.getValue());
        ext.put("op", condition.getOp());

        AlarmRecordRow row = insertAndMark(rule, msg.getTenantId(), msg.getDeviceId(), msg.getProductKey(),
                level, TRIGGER_PROPERTY, message, ext);
        if (row != null) {
            log.warn("[Alarm] 告警触发 ruleId={} ruleCode={} deviceId={} {} 当前={}",
                    rule.getRuleId(), rule.getRuleCode(), msg.getDeviceId(), condition.getMetric(), value);
        }
    }

    private void fireEvent(AlarmRuleRow rule, ThingEventMessage msg) {
        if (isSilenced(rule.getRuleId(), msg.getDeviceId())) {
            return;
        }
        int level = msg.getSeverity() != null ? msg.getSeverity()
                : (rule.getSeverity() == null ? 3 : rule.getSeverity());
        String message = rule.getRuleName() + "：" + msg.getEventName();
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("event", msg.getEventName());
        if (msg.getData() != null && !msg.getData().isEmpty()) {
            ext.put("data", msg.getData());
        }
        AlarmRecordRow row = insertAndMark(rule, msg.getTenantId(), msg.getDeviceId(), msg.getProductKey(),
                level, TRIGGER_EVENT, message, ext);
        if (row != null) {
            log.warn("[Alarm] 事件告警触发 ruleId={} ruleCode={} deviceId={} event={} level={}",
                    rule.getRuleId(), rule.getRuleCode(), msg.getDeviceId(), msg.getEventName(), level);
        }
    }

    /** 落库 + 置静默 + 发布（Kafka/WS/ES）。返回插入行；静默期内返回 null。 */
    private AlarmRecordRow insertAndMark(AlarmRuleRow rule, Long tenantId, long deviceId, String productKey,
                                         int level, int type, String message, Map<String, Object> ext) {
        String alarmEventId = idGenerator.nextIdStr();
        LocalDateTime now = LocalDateTime.now();
        int inserted = recordMapper.insert(alarmEventId, tenantId, deviceId, productKey,
                rule.getRuleId(), rule.getRuleCode(), level, type, message, toJson(ext), now);
        if (inserted <= 0) {
            return null;
        }
        // 静默 SETNX：原子防抖，多实例/重放下同规则+设备仅一条
        redis.opsForValue().setIfAbsent(AlarmRedisKeys.silence(rule.getRuleId(), deviceId),
                String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(rule.getSilenceSeconds() == null ? 300 : rule.getSilenceSeconds()));

        AlarmMessage alarm = new AlarmMessage();
        alarm.setAlarmEventId(alarmEventId);
        alarm.setTenantId(tenantId);
        alarm.setDeviceId(deviceId);
        alarm.setProductKey(productKey);
        alarm.setRuleId(rule.getRuleId());
        alarm.setRuleCode(rule.getRuleCode());
        alarm.setLevel(level);
        alarm.setType(type);
        alarm.setStatus("ACTIVE");
        alarm.setMessage(message);
        alarm.setExt(ext);
        alarm.setTs(System.currentTimeMillis());
        publish(alarm);
        return rowOf(alarm);
    }

    // ------------------------------------------------------------------
    // 持续窗口 / 静默 / 恢复
    // ------------------------------------------------------------------

    /** 持续窗口判定：首次违反记时间戳，满 windowSec 返回 true；windowSec=0/缺省立即触发 */
    private boolean isSustained(AlarmRuleRow rule, long deviceId, Integer windowSec) {
        if (windowSec == null || windowSec <= 0) {
            return true; // 无窗口要求，立即触发（防抖交给静默期）
        }
        long windowMs = windowSec * 1000L;
        String key = AlarmRedisKeys.sustain(rule.getRuleId(), deviceId);
        String first = redis.opsForValue().get(key);
        long now = System.currentTimeMillis();
        if (first == null) {
            // 首次违反：记录时刻，本次不算告警，等窗口期满
            redis.opsForValue().setIfAbsent(key, String.valueOf(now),
                    Duration.ofSeconds(windowSec + props.getSustainKeyBufferSeconds()));
            return false;
        }
        try {
            return now - Long.parseLong(first) >= windowMs;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void resetSustain(AlarmRuleRow rule, long deviceId) {
        redis.delete(AlarmRedisKeys.sustain(rule.getRuleId(), deviceId));
    }

    private boolean isSilenced(long ruleId, long deviceId) {
        return Boolean.TRUE.equals(redis.hasKey(AlarmRedisKeys.silence(ruleId, deviceId)));
    }

    /** 值回正常区间：显式 recovery 条件命中，或无 recovery 时条件不满足即恢复 */
    private void tryRecover(AlarmRuleRow rule, ThingPropertyMessage msg, Object value, AlarmCondition trigger) {
        boolean recovered;
        if (rule.getRecovery() != null && !rule.getRecovery().isBlank()) {
            AlarmCondition rc = parseCondition(rule.getRecovery());
            if (rc.getMetric() == null) {
                return;
            }
            Object rv = msg.getProperties().get(rc.getMetric());
            if (rv == null) {
                return;
            }
            recovered = AlarmRuleEngine.recoveryMet(rc, rv);
        } else {
            // 无显式恢复条件：触发条件不再满足即视为恢复正常
            recovered = true;
        }
        if (recovered) {
            recoverActive(rule, msg.getDeviceId());
        }
    }

    private void recoverActive(AlarmRuleRow rule, long deviceId) {
        List<AlarmRecordRow> actives = recordMapper.selectActiveRecords(rule.getRuleId(), deviceId);
        int updated = recordMapper.recoverActive(rule.getRuleId(), deviceId, LocalDateTime.now());
        if (updated > 0) {
            redis.delete(AlarmRedisKeys.sustain(rule.getRuleId(), deviceId));
            redis.delete(AlarmRedisKeys.silence(rule.getRuleId(), deviceId));
            for (AlarmRecordRow r : actives) {
                publishRecovered(r);
            }
            log.info("[Alarm] 告警恢复 ruleId={} deviceId={} count={}", rule.getRuleId(), deviceId, actives.size());
        } else {
            // 无触发中记录：仅重置持续窗口（恢复广播已由其他路径/人工确认覆盖）
            redis.delete(AlarmRedisKeys.sustain(rule.getRuleId(), deviceId));
        }
    }

    private void publishRecovered(AlarmRecordRow r) {
        AlarmMessage m = new AlarmMessage();
        m.setAlarmEventId(r.getAlarmEventId());
        m.setTenantId(r.getTenantId());
        m.setDeviceId(r.getDeviceId());
        m.setProductKey(r.getProductKey());
        m.setRuleId(r.getRuleId());
        m.setRuleCode(r.getRuleCode());
        m.setLevel(r.getLevel());
        m.setType(r.getType());
        m.setStatus("RECOVERED");
        m.setMessage(r.getMessage());
        m.setExt(parse(r.getExt()));
        m.setTs(System.currentTimeMillis());
        publish(m);
    }

    // ------------------------------------------------------------------
    // 发布
    // ------------------------------------------------------------------

    private void publish(AlarmMessage m) {
        String json = toJson(m);
        try {
            publisher.send(KafkaTopicConstant.IOT_ALARM, String.valueOf(m.getDeviceId()), json);
        } catch (Exception e) {
            log.warn("[Alarm] iot-alarm 发布失败 alarmEventId={}", m.getAlarmEventId(), e);
        }
        try {
            publisher.broadcast(json);
        } catch (Exception e) {
            log.warn("[Alarm] WS 广播失败 alarmEventId={}", m.getAlarmEventId(), e);
        }
        publisher.writeEs(m);
    }

    // ------------------------------------------------------------------
    // 查询 / 确认
    // ------------------------------------------------------------------

    public PageResult<AlarmRecordView> queryRecords(Long tenantId, Long ruleId, Long deviceId, Integer level,
                                                    Integer status, LocalDateTime startTime, LocalDateTime endTime,
                                                    long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(200, Math.max(1, size));
        long offset = (safePage - 1) * safeSize;
        long total = recordMapper.count(tenantId, ruleId, deviceId, level, status, startTime, endTime);
        List<AlarmRecordView> records = recordMapper
                .selectPage(tenantId, ruleId, deviceId, level, status, startTime, endTime, offset, safeSize)
                .stream().map(this::toView).toList();
        return PageResult.of(total, safePage, safeSize, records);
    }

    /** 人工确认告警：触发中/已恢复可确认，终态重复确认幂等空操作 */
    public boolean ackAlarm(String alarmEventId, String ackedBy) {
        int updated = recordMapper.ack(alarmEventId, ackedBy, LocalDateTime.now());
        return updated > 0;
    }

    public List<AlarmRuleRow> listRules(Long tenantId) {
        if (tenantId == null) {
            return new ArrayList<>(ruleCache);
        }
        return ruleCache.stream()
                .filter(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId))
                .toList();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** product_key → product_id 本地缓存（TTL 防陈旧） */
    private Long resolveProductId(String productKey) {
        if (productKey == null || productKey.isBlank()) {
            return null;
        }
        ProductCacheEntry entry = productCache.get(productKey);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadTime < props.getProductCacheTtlMs()) {
            return entry.productId;
        }
        Long productId = null;
        try {
            productId = productMapper.selectProductIdByKey(productKey);
        } catch (Exception e) {
            log.warn("[Alarm] product_key 解析失败 productKey={}", productKey, e);
        }
        productCache.put(productKey, new ProductCacheEntry(productId, now));
        return productId;
    }

    private boolean matchesRule(AlarmRuleRow rule, Long tenantId, Long deviceId, Long productId) {
        if (rule.getStatus() == null || rule.getStatus() != 1) {
            return false;
        }
        if (tenantId != null && rule.getTenantId() != null && !rule.getTenantId().equals(tenantId)) {
            return false;
        }
        if (rule.getDeviceId() != null && !rule.getDeviceId().equals(deviceId)) {
            return false;
        }
        if (rule.getProductId() != null && !rule.getProductId().equals(productId)) {
            return false;
        }
        return true;
    }

    private AlarmCondition parseCondition(String json) {
        if (json == null || json.isBlank()) {
            return new AlarmCondition();
        }
        try {
            return objectMapper.readValue(json, AlarmCondition.class);
        } catch (Exception e) {
            log.warn("[Alarm] 条件 JSON 解析失败 json={}", json, e);
            return new AlarmCondition();
        }
    }

    private AlarmRecordView toView(AlarmRecordRow row) {
        AlarmRecordView view = new AlarmRecordView();
        view.setAlarmEventId(row.getAlarmEventId());
        view.setTenantId(row.getTenantId());
        view.setDeviceId(row.getDeviceId());
        view.setProductKey(row.getProductKey());
        view.setRuleId(row.getRuleId());
        view.setRuleCode(row.getRuleCode());
        view.setLevel(row.getLevel());
        view.setType(row.getType());
        view.setStatus(row.getStatus());
        view.setStatusName(statusName(row.getStatus()));
        view.setMessage(row.getMessage());
        view.setExt(parse(row.getExt()));
        view.setTriggeredTime(row.getTriggeredTime());
        view.setRecoveredTime(row.getRecoveredTime());
        view.setAckedBy(row.getAckedBy());
        view.setAckTime(row.getAckTime());
        return view;
    }

    private String statusName(Integer status) {
        if (status == null) {
            return "ACTIVE";
        }
        return switch (status) {
            case 1 -> "RECOVERED";
            case 2 -> "ACKED";
            default -> "ACTIVE";
        };
    }

    private AlarmRecordRow rowOf(AlarmMessage m) {
        AlarmRecordRow row = new AlarmRecordRow();
        row.setAlarmEventId(m.getAlarmEventId());
        row.setTenantId(m.getTenantId());
        row.setDeviceId(m.getDeviceId());
        row.setProductKey(m.getProductKey());
        row.setRuleId(m.getRuleId());
        row.setRuleCode(m.getRuleCode());
        row.setLevel(m.getLevel());
        row.setType(m.getType());
        row.setStatus(0);
        row.setMessage(m.getMessage());
        return row;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("[Alarm] JSON 解析失败 json={}", json, e);
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("告警 JSON 序列化失败: " + value, e);
        }
    }

    /** product_key → product_id 缓存项 */
    private record ProductCacheEntry(Long productId, long loadTime) {
    }
}
