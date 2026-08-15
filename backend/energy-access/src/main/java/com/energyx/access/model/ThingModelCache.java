package com.energyx.access.model;

import com.energyx.access.client.ProductFeignClient;
import com.energyx.access.client.ThingModelRow;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.util.AccessKeys;
import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物模型缓存（两级 Cache-Aside）： <pre>
 * L1 本地 ConcurrentHashMap（10min，按 productKey）→ L2 Redis cache:model:current:{pk}
 *   → product 服务（Feign，替代跨库直查 es_product）→ 逐级回填
 * </pre>
 *
 * <p>
 * 物模型变更频率极低（版本发布才变），L1 命中率近 100%，上行热路径零 Redis 往返。 L1 采用「满 1000
 * 直接清空」的极简有界策略：产品数远小于此，清空代价可忽略。
 * </p>
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

	private final ProductFeignClient productFeignClient;

	private final AccessProperties props;

	public ThingModelCache(StringRedisTemplate redis, ProductFeignClient productFeignClient, AccessProperties props) {
		this.redis = redis;
		this.productFeignClient = productFeignClient;
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
		}
		catch (Exception e) {
			log.warn("[Access] 物模型缓存读取/解析失败 productKey={}", productKey, e);
		}
		// 回源：Feign 调 product 服务取当前生效物模型（未发布/服务不可用返回 null）
		Result<ThingModelRow> result = productFeignClient.getThingModelByKey(productKey);
		if (result == null || !result.isSuccess() || result.getData() == null) {
			return null;
		}
		ThingModelRow row = result.getData();
		try {
			ThingModel model = ThingModelParser.parse(row.schemaJson());
			model.setVersion(row.version());
			redis.opsForValue().set(key, row.schemaJson(), Duration.ofSeconds(props.getModelCacheTtlSeconds()));
			putLocal(productKey, model);
			return model;
		}
		catch (Exception e) {
			log.error("[Access] 物模型解析失败 productKey={}", productKey, e);
			return null;
		}
	}

	private void putLocal(String productKey, ThingModel model) {
		if (local.size() >= LOCAL_MAX) {
			local.clear();
		}
		local.put(productKey,
				new LocalEntry(System.currentTimeMillis() + props.getModelCacheTtlSeconds() * 1000L, model));
	}

}
