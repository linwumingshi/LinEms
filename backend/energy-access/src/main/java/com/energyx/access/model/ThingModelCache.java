package com.energyx.access.model;

import com.energyx.access.config.AccessProperties;
import com.energyx.access.mapper.ThingModelMapper;
import com.energyx.access.util.AccessKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物模型缓存（两级 Cache-Aside）：
 * <pre>
 * L1 本地 ConcurrentHashMap（10min，按 productKey）→ L2 Redis cache:model:current:{pk}
 *   → MySQL iot_product + iot_thing_model（当前生效版本）→ 逐级回填
 * </pre>
 *
 * <p>物模型变更频率极低（版本发布才变），L1 命中率近 100%，上行热路径零 Redis 往返。
 * L1 采用「满 1000 直接清空」的极简有界策略：产品数远小于此，清空代价可忽略。</p>
 */
@Slf4j
@Component
public class ThingModelCache {

    private static final int LOCAL_MAX = 1000;

    private static final class LocalEntry {
        final long expireAt;
        final ThingModel model;

        LocalEntry(long expireAt, ThingModel model) {
            this.expireAt = expireAt;
            this.model = model;
        }
    }

    private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ThingModelMapper mapper;
    private final AccessProperties props;

    public ThingModelCache(StringRedisTemplate redis, ThingModelMapper mapper, AccessProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 取产品当前生效物模型；未发布/禁用返回 null（调用方跳过该产品的标准化，不阻塞链路）。
     */
    public ThingModel get(String productKey) {
        LocalEntry le = local.get(productKey);
        if (le != null && le.expireAt > System.currentTimeMillis()) {
            return le.model;
        }
        String key = AccessKeys.modelCurrent(productKey);
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                ThingModel model = ThingModelParser.parse(json);
                putLocal(productKey, model);
                return model;
            }
        } catch (Exception e) {
            log.warn("[Access] 物模型缓存读取/解析失败 productKey={}", productKey, e);
        }
        ThingModelMapper.ModelRow row = mapper.loadCurrentModel(productKey);
        if (row == null) {
            return null;
        }
        try {
            ThingModel model = ThingModelParser.parse(row.getSchemaJson());
            model.setVersion(row.getModelVersion());
            redis.opsForValue().set(key, row.getSchemaJson(),
                    Duration.ofSeconds(props.getModelCacheTtlSeconds()));
            putLocal(productKey, model);
            return model;
        } catch (Exception e) {
            log.error("[Access] 物模型解析失败 productKey={}", productKey, e);
            return null;
        }
    }

    private void putLocal(String productKey, ThingModel model) {
        if (local.size() >= LOCAL_MAX) {
            local.clear();
        }
        local.put(productKey, new LocalEntry(
                System.currentTimeMillis() + props.getModelCacheTtlSeconds() * 1000L, model));
    }
}
