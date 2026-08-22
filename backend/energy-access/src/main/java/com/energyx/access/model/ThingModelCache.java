package com.energyx.access.model;

import com.energyx.access.client.ProductFeignClient;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.util.AccessKeys;
import com.energyx.common.model.Result;
import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelParser;
import com.energyx.common.thingmodel.ThingModelRow;
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

	/** L1 本地缓存上限：达到即整体清空（产品数远小于此，清空代价可忽略） */
	private static final int LOCAL_MAX = 1000;

	/** L1 本地缓存条目（不可变）：承载过期时间戳与物模型快照 */
	private static final class LocalEntry {

		/** 过期时间戳（毫秒），到期即视为未命中需回源 */
		final long expireAt;

		/** 物模型快照（解析后的 {@link ThingModel}） */
		final ThingModel model;

		/** 构造缓存条目 */
		LocalEntry(long expireAt, ThingModel model) {
			this.expireAt = expireAt;
			this.model = model;
		}

	}

	/** L1 本地缓存：productKey → 物模型条目（有界、热路径零 Redis 往返） */
	private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();

	/** Redis 模板（L2 缓存 cache:model:current:{pk} 读写） */
	private final StringRedisTemplate redis;

	/** 产品服务 Feign 客户端（L2 回源取当前生效物模型） */
	private final ProductFeignClient productFeignClient;

	/** 接入配置（TTL 等缓存参数来源） */
	private final AccessProperties props;

	/** 构造缓存组件并注入 L2 Redis / 产品 Feign / 配置依赖 */
	public ThingModelCache(StringRedisTemplate redis, ProductFeignClient productFeignClient, AccessProperties props) {
		this.redis = redis;
		this.productFeignClient = productFeignClient;
		this.props = props;
	}

	/**
	 * 取产品当前生效物模型；未发布/禁用返回 null（调用方跳过该产品的标准化，不阻塞链路）。
	 * @param productKey 产品标识
	 * @return 命中缓存或回源解析得到的 {@link ThingModel}；无物模型时返回 null
	 */
	public ThingModel get(String productKey) {
		// 1. L1 本地命中直接返回（热路径零 Redis 往返）
		LocalEntry le = local.get(productKey);
		if (le != null && le.expireAt > System.currentTimeMillis()) {
			return le.model;
		}
		String key = AccessKeys.modelCurrent(productKey);
		try {
			// 2. L2 Redis 命中 → 解析并回填 L1
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

	/** 写入 L1 本地缓存：达到上限先整体清空（极简有界策略），再追加带过期时间的条目 */
	private void putLocal(String productKey, ThingModel model) {
		// 达到上限整体清空：产品数远小于 1000，清空代价可忽略
		if (local.size() >= LOCAL_MAX) {
			local.clear();
		}
		local.put(productKey,
				new LocalEntry(System.currentTimeMillis() + props.getModelCacheTtlSeconds() * 1000L, model));
	}

}
