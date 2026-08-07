package com.energyx.access.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.mapper.DeviceMapper;
import com.energyx.access.util.AccessKeys;
import com.energyx.common.mqtt.MqttTopicUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 设备信息缓存（Cache-Aside）：Redis cache:device:{deviceKey} → MySQL iot_device 兜底。
 *
 * <p>上行热路径每报文一次查询，必须避开 MySQL；设备维度的 tenant/station/enterprise
 * 稳定不变（归属变更属低频运维操作，走主动失效），30min TTL 足够。</p>
 */
@Slf4j
@Component
public class DeviceInfoCache {

    private final StringRedisTemplate redis;
    private final DeviceMapper deviceMapper;
    private final AccessProperties props;
    private final ObjectMapper objectMapper;

    public DeviceInfoCache(StringRedisTemplate redis, DeviceMapper deviceMapper,
                           AccessProperties props, ObjectMapper objectMapper) {
        this.redis = redis;
        this.deviceMapper = deviceMapper;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 取设备上下文；未注册（或已删除）返回 null（调用方按 DEVICE_NOT_FOUND 拒绝）。
     */
    public DeviceInfo get(String productKey, String deviceName) {
        String deviceKey = MqttTopicUtil.buildDeviceKey(productKey, deviceName);
        String key = AccessKeys.deviceInfo(deviceKey);
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, DeviceInfo.class);
            }
        } catch (Exception e) {
            log.warn("[Access] 设备缓存读取失败 deviceKey={}", deviceKey, e);
        }
        DeviceInfo info = deviceMapper.findByProductAndName(productKey, deviceName);
        if (info != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(info),
                        Duration.ofSeconds(props.getDeviceCacheTtlSeconds()));
            } catch (Exception e) {
                log.warn("[Access] 设备缓存写入失败 deviceKey={}", deviceKey, e);
            }
        }
        return info;
    }
}
