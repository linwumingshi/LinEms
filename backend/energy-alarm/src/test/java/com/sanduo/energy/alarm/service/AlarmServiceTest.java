package com.sanduo.energy.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.alarm.config.AlarmProperties;
import com.sanduo.energy.alarm.mapper.AlarmRecordMapper;
import com.sanduo.energy.alarm.mapper.AlarmRuleMapper;
import com.sanduo.energy.alarm.mapper.ProductInfoMapper;
import com.sanduo.energy.alarm.model.AlarmRecordRow;
import com.sanduo.energy.alarm.model.AlarmRuleRow;
import com.sanduo.energy.alarm.web.dto.AlarmRecordView;
import com.sanduo.energy.common.constant.KafkaTopicConstant;
import com.sanduo.energy.common.message.AlarmMessage;
import com.sanduo.energy.common.message.ThingEventMessage;
import com.sanduo.energy.common.message.ThingPropertyMessage;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlarmService 核心路径测试（Mock Mapper/Redis/发布门面，ObjectMapper/雪花/配置用真实实现）。
 *
 * <p>覆盖：属性规则（持续窗口判定/静默防抖/恢复）、事件规则（命中/不命中/级别取事件）、
 * 产品作用域匹配、分页查询、人工确认幂等。</p>
 */
@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

    @Mock
    AlarmRuleMapper ruleMapper;
    @Mock
    AlarmRecordMapper recordMapper;
    @Mock
    ProductInfoMapper productMapper;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    AlarmKafkaPublisher publisher;

    AlarmService service;
    AlarmProperties props;

    @BeforeEach
    void setUp() {
        props = new AlarmProperties();
        service = new AlarmService(ruleMapper, recordMapper, productMapper, redis, props,
                publisher, new SnowflakeIdGenerator(), new ObjectMapper());
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(tempRule(), eventRule(), productRule()));
        service.init();
    }

    // ---------------------------------------------------------- helpers

    private static AlarmRuleRow tempRule() {
        AlarmRuleRow r = new AlarmRuleRow();
        r.setRuleId(1L);
        r.setTenantId(1L);
        r.setRuleCode("ALM_TEMP_HIGH");
        r.setRuleName("温度过高");
        r.setTriggerType(1);
        r.setCondition("{\"metric\":\"temp\",\"op\":\"GTE\",\"value\":60,\"windowSec\":60}");
        r.setSeverity(3);
        r.setSilenceSeconds(300);
        r.setStatus(1);
        return r;
    }

    private static AlarmRuleRow eventRule() {
        AlarmRuleRow r = new AlarmRuleRow();
        r.setRuleId(2L);
        r.setTenantId(1L);
        r.setRuleCode("ALM_BMS_FAULT");
        r.setRuleName("BMS 故障");
        r.setTriggerType(2);
        r.setCondition("{\"event\":\"bmsFault\"}");
        r.setSeverity(2);
        r.setSilenceSeconds(300);
        r.setStatus(1);
        return r;
    }

    private static AlarmRuleRow productRule() {
        AlarmRuleRow r = new AlarmRuleRow();
        r.setRuleId(3L);
        r.setTenantId(1L);
        r.setProductId(10L);
        r.setRuleCode("ALM_SOC_LOW");
        r.setRuleName("SOC 过低");
        r.setTriggerType(1);
        r.setCondition("{\"metric\":\"soc\",\"op\":\"LT\",\"value\":20,\"windowSec\":0}");
        r.setSeverity(4);
        r.setSilenceSeconds(60);
        r.setStatus(1);
        return r;
    }

    private static ThingPropertyMessage propertyMsg(Map<String, Object> props, String productKey) {
        ThingPropertyMessage m = new ThingPropertyMessage();
        m.setMessageId("m-1");
        m.setDeviceId(100L);
        m.setTenantId(1L);
        m.setProductKey(productKey);
        m.setProperties(props);
        return m;
    }

    private static ThingEventMessage eventMsg(String eventName, Integer severity) {
        ThingEventMessage m = new ThingEventMessage();
        m.setMessageId("m-1");
        m.setEventId("e-1");
        m.setDeviceId(100L);
        m.setTenantId(1L);
        m.setProductKey("pk-1");
        m.setEventName(eventName);
        m.setSeverity(severity);
        m.setData(Map.of("code", 1));
        return m;
    }

    private static AlarmRecordRow activeRow() {
        AlarmRecordRow r = new AlarmRecordRow();
        r.setAlarmEventId("evt-recovered");
        r.setTenantId(1L);
        r.setDeviceId(100L);
        r.setProductKey("pk-1");
        r.setRuleId(1L);
        r.setRuleCode("ALM_TEMP_HIGH");
        r.setLevel(3);
        r.setType(1);
        r.setStatus(0);
        r.setMessage("温度过高");
        r.setExt("{\"metric\":\"temp\",\"currentValue\":80,\"threshold\":60}");
        return r;
    }

    // ---------------------------------------------------------- property rule

    @Test
    @DisplayName("属性超阈且满持续窗口 → 触发告警（落库/静默/发布）")
    void propertyRule_firesWhenSustained() {
        when(valueOps.get("alarm:sustain:1:100"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 70_000));
        when(redis.hasKey("alarm:silence:1:100")).thenReturn(false);
        when(recordMapper.insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString(),
                anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        service.handlePropertyReport(propertyMsg(Map.of("temp", 80), "pk-1"));

        verify(recordMapper).insert(anyString(), eq(1L), eq(100L), eq("pk-1"), eq(1L), eq("ALM_TEMP_HIGH"),
                eq(3), eq(1), anyString(), anyString(), any(LocalDateTime.class));
        verify(valueOps).setIfAbsent(eq("alarm:silence:1:100"), anyString(), any(Duration.class));
        verify(publisher).send(eq(KafkaTopicConstant.IOT_ALARM), eq("100"), contains("ALM_TEMP_HIGH"));
        verify(publisher).broadcast(anyString());
        verify(publisher).writeEs(any(AlarmMessage.class));
    }

    @Test
    @DisplayName("属性超阈但未满持续窗口 → 记首违反，不触发")
    void propertyRule_notFiredWithinWindow() {
        when(valueOps.get("alarm:sustain:1:100")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.handlePropertyReport(propertyMsg(Map.of("temp", 80), "pk-1"));

        verify(recordMapper, never()).insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                anyString(), anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class));
        verify(valueOps, never()).setIfAbsent(eq("alarm:sustain:1:100"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("静默期内同规则 → 防抖跳过")
    void propertyRule_silencedSkips() {
        when(valueOps.get("alarm:sustain:1:100"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 70_000));
        when(redis.hasKey("alarm:silence:1:100")).thenReturn(true);

        service.handlePropertyReport(propertyMsg(Map.of("temp", 80), "pk-1"));

        verify(recordMapper, never()).insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                anyString(), anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("值回正常（无显式恢复条件）→ 触发中记录批量恢复 + RECOVERED 发布")
    void propertyRule_recoversWhenNormal() {
        // 第一次上报触发
        when(valueOps.get("alarm:sustain:1:100"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 70_000));
        when(redis.hasKey("alarm:silence:1:100")).thenReturn(false);
        when(recordMapper.insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString(),
                anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);
        service.handlePropertyReport(propertyMsg(Map.of("temp", 80), "pk-1"));

        // 第二次上报回正常：恢复
        when(recordMapper.selectActiveRecords(1L, 100L)).thenReturn(List.of(activeRow()));
        when(recordMapper.recoverActive(eq(1L), eq(100L), any(LocalDateTime.class))).thenReturn(1);

        service.handlePropertyReport(propertyMsg(Map.of("temp", 50), "pk-1"));

        verify(recordMapper).recoverActive(eq(1L), eq(100L), any(LocalDateTime.class));
        // 持续窗口键被 resetSustain 与 recoverActive 各删一次（幂等删除）
        verify(redis, atLeastOnce()).delete("alarm:sustain:1:100");
        verify(redis).delete("alarm:silence:1:100");
        verify(publisher).send(eq(KafkaTopicConstant.IOT_ALARM), eq("100"), contains("RECOVERED"));
    }

    // ---------------------------------------------------------- event rule

    @Test
    @DisplayName("事件命中 → 级别取事件携带 severity（高于规则默认）")
    void eventRule_firesWithEventSeverity() {
        when(redis.hasKey("alarm:silence:2:100")).thenReturn(false);
        when(recordMapper.insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString(),
                anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        service.handleEventReport(eventMsg("bmsFault", 3));

        verify(recordMapper).insert(anyString(), eq(1L), eq(100L), eq("pk-1"), eq(2L), eq("ALM_BMS_FAULT"),
                eq(3), eq(2), anyString(), anyString(), any(LocalDateTime.class));
        verify(publisher).send(eq(KafkaTopicConstant.IOT_ALARM), eq("100"), contains("bmsFault"));
    }

    @Test
    @DisplayName("事件不匹配规则 → 不触发")
    void eventRule_notMatchedSkips() {
        service.handleEventReport(eventMsg("overVolt", 2));

        verify(recordMapper, never()).insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                anyString(), anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------- product scope

    @Test
    @DisplayName("产品级规则：product_key 解析 product_id 后命中")
    void productScopedRuleMatches() {
        when(productMapper.selectProductIdByKey("pk-1")).thenReturn(10L);
        when(redis.hasKey("alarm:silence:3:100")).thenReturn(false);
        when(recordMapper.insert(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString(),
                anyInt(), anyInt(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("soc", 5);
        service.handlePropertyReport(propertyMsg(props, "pk-1"));

        verify(recordMapper).insert(anyString(), eq(1L), eq(100L), eq("pk-1"), eq(3L), eq("ALM_SOC_LOW"),
                eq(4), eq(1), anyString(), anyString(), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------- query / ack

    @Test
    @DisplayName("分页查询：组合条件 + 分页参数透传")
    void queryRecords_pagesAndFilters() {
        when(recordMapper.count(eq(1L), any(), any(), any(), any(), any(), any())).thenReturn(5L);
        when(recordMapper.selectPage(eq(1L), any(), any(), any(), any(), any(), any(),
                eq(20L), eq(20L))).thenReturn(List.of(activeRow()));

        PageResult<AlarmRecordView> result =
                service.queryRecords(1L, null, null, null, null, null, null, 2, 20);

        assertEquals(5, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals(20, result.getSize());
        assertEquals(1, result.getRecords().size());
        assertEquals("ALM_TEMP_HIGH", result.getRecords().get(0).getRuleCode());
    }

    @Test
    @DisplayName("确认：命中返回 true；不存在/已终态返回 false")
    void ackAlarm_idempotent() {
        when(recordMapper.ack(eq("evt-1"), eq("ops-001"), any(LocalDateTime.class))).thenReturn(1);
        when(recordMapper.ack(eq("evt-2"), eq("ops-001"), any(LocalDateTime.class))).thenReturn(0);

        assertTrue(service.ackAlarm("evt-1", "ops-001"));
        assertFalse(service.ackAlarm("evt-2", "ops-001"));
    }
}
