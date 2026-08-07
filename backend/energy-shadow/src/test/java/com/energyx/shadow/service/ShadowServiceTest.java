package com.energyx.shadow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.shadow.config.ShadowProperties;
import com.energyx.shadow.delta.ShadowDeltaPublisher;
import com.energyx.shadow.mapper.ShadowHistoryMapper;
import com.energyx.shadow.mapper.ShadowMapper;
import com.energyx.shadow.model.ShadowRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShadowService 核心路径测试（Mockito mock 掉 Mapper/Redis，ObjectMapper 与 DeltaCalculator 用真实实现）。
 *
 * <p>覆盖：无行插入、合并更新、版本冲突重试、同值跳过、desired delta 发布、收敛不发 delta、
 * 乐观锁重试耗尽抛错。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShadowServiceTest {

    @Mock
    ShadowMapper shadowMapper;
    @Mock
    ShadowHistoryMapper historyMapper;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ShadowDeltaPublisher deltaPublisher;
    @Mock
    HashOperations<String, Object, Object> hashOps;
    @Mock
    ValueOperations<String, String> valueOps;

    ShadowService service;

    @BeforeEach
    void setUp() {
        ShadowProperties props = new ShadowProperties();
        props.setHistoryEnabled(true);
        props.setHistoryThrottleSeconds(60);
        props.setOptimisticMaxRetry(3);
        props.setReportedTtlDays(7);
        service = new ShadowService(shadowMapper, historyMapper, redis, deltaPublisher,
                new ObjectMapper(), props);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        lenient().when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ShadowRow row(String reported, int version) {
        ShadowRow r = new ShadowRow();
        r.setDeviceId(100L);
        r.setReported(reported);
        r.setDesired("{}");
        r.setVersion(version);
        return r;
    }

    // ---------------------------------------------------------- applyReported

    @Test
    @DisplayName("无行 → 插入初始化 reported，落 Redis 热缓存与变更历史")
    void applyReported_insertWhenNoRow() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(null);
        when(shadowMapper.insertReported(eq(100L), eq(1L), anyString(), any(LocalDateTime.class))).thenReturn(1);

        service.applyReported(100L, 1L, props("soc", 88.5));

        verify(shadowMapper).insertReported(eq(100L), eq(1L), contains("88.5"), any(LocalDateTime.class));
        verify(hashOps).put("iot:shadow:reported:100", "soc", "88.5");
        verify(redis).expire("iot:shadow:reported:100", Duration.ofDays(7));
        verify(historyMapper).insert(eq(100L), eq(1), anyString(), eq(1));
    }

    @Test
    @DisplayName("已有行 → 合并更新（保留未上报属性），版本 +1")
    void applyReported_mergeAndUpdate() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row("{\"soc\":88.5,\"voltage\":720}", 5));
        when(shadowMapper.updateReported(eq(100L), anyString(), eq(5), any(LocalDateTime.class))).thenReturn(1);

        service.applyReported(100L, 1L, props("voltage", 730));

        verify(shadowMapper).updateReported(eq(100L), contains("\"soc\":88.5"), eq(5), any(LocalDateTime.class));
        verify(shadowMapper).updateReported(eq(100L), contains("\"voltage\":730"), eq(5), any(LocalDateTime.class));
        verify(historyMapper).insert(eq(100L), eq(6), contains("730"), eq(1));
    }

    @Test
    @DisplayName("版本冲突 → 重试后收敛（乐观锁最大重试内）")
    void applyReported_versionConflictRetry() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row("{\"a\":1,\"b\":2}", 5));
        when(shadowMapper.updateReported(eq(100L), anyString(), eq(5), any(LocalDateTime.class)))
                .thenReturn(0).thenReturn(1);

        service.applyReported(100L, 1L, props("b", 3));

        verify(shadowMapper, times(2)).updateReported(eq(100L), anyString(), eq(5), any(LocalDateTime.class));
        verify(historyMapper).insert(eq(100L), eq(6), anyString(), eq(1));
    }

    @Test
    @DisplayName("同值上报 → 跳过写库与历史，仅刷 Redis")
    void applyReported_noChangeSkipsUpdate() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row("{\"a\":1}", 3));

        service.applyReported(100L, 1L, props("a", 1));

        verify(shadowMapper, never()).updateReported(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
        verify(historyMapper, never()).insert(anyLong(), anyInt(), anyString(), anyInt());
        verify(hashOps).put("iot:shadow:reported:100", "a", "1");
    }

    @Test
    @DisplayName("乐观锁重试耗尽 → 抛 IllegalStateException")
    void applyReported_retryExhaustedThrows() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row("{\"a\":1}", 5));
        when(shadowMapper.updateReported(eq(100L), anyString(), eq(5), any(LocalDateTime.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.applyReported(100L, 1L, props("b", 3)));
        verify(shadowMapper, times(3)).updateReported(anyLong(), anyString(), eq(5), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------- setDesired

    @Test
    @DisplayName("desired 与 reported 不一致 → 写 desired + 发布 delta")
    void setDesired_publishDelta() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row("{}", 2));
        when(shadowMapper.updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class))).thenReturn(1);
        when(hashOps.entries("iot:shadow:reported:100")).thenReturn(Map.of("power", "50"));

        ShadowService.DesiredResult result = service.setDesired(100L, 1L, props("power", 100));

        assertEquals(1, result.delta().size());
        assertEquals(100, result.delta().get("power"));
        verify(hashOps).put("iot:shadow:desired:100", "power", "100");
        verify(deltaPublisher).publish(eq(100L), eq(1L), eq(2), argThat(d -> d.containsKey("power")));
        verify(historyMapper).insert(eq(100L), eq(2), anyString(), eq(2));
    }

    @Test
    @DisplayName("desired 已与 reported 收敛 → 不发布 delta、不落历史")
    void setDesired_convergedNoDelta() {
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row("{}", 2));
        when(shadowMapper.updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class))).thenReturn(1);
        when(hashOps.entries("iot:shadow:reported:100")).thenReturn(Map.of("power", "100"));

        ShadowService.DesiredResult result = service.setDesired(100L, 1L, props("power", 100));

        assertTrue(result.delta().isEmpty());
        verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
        verify(historyMapper, never()).insert(anyLong(), anyInt(), anyString(), anyInt());
    }

    @Test
    @DisplayName("reported 热缓存未命中 → 回 MySQL 兜底（getShadow）")
    void getShadow_mysqlFallback() {
        when(hashOps.entries("iot:shadow:reported:100")).thenReturn(Map.of());
        when(hashOps.entries("iot:shadow:desired:100")).thenReturn(Map.of());
        ShadowRow row = row("{\"soc\":88.5}", 4);
        row.setDesired("{\"power\":100}");
        when(shadowMapper.selectByDeviceId(100L)).thenReturn(row);

        var view = service.getShadow(100L);

        assertEquals(88.5, view.getReported().get("soc"));
        assertEquals(100, view.getDesired().get("power"));
        assertEquals(4, view.getVersion());
    }
}
