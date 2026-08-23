package com.energyx.common.thingmodel;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物模型获取/解析统一入口（M2.4：Command/Shadow 校验链路共享，含 L1 本地缓存）。
 *
 * <p>
 * {@code resolve(productKey) → ThingModel}，内部流程：
 * <ol>
 * <li>productKey 空值 → null；</li>
 * <li>L1 缓存命中（未过期）→ 直接返回解析后的 {@link ThingModel}；</li>
 * <li>未命中 → {@link ThingModelFetcher#fetch}；</li>
 * <li>Result 失败 / data 为 null / schemaJson 为空 → null（不缓存）；</li>
 * <li>{@link ThingModelParser#parse} 成功 → 写缓存并返回；失败 → null（不缓存）。</li>
 * </ol>
 * </p>
 *
 * <p>
 * <b>缓存语义</b>：缓存「解析后的 ThingModel」（非 ThingModelRow），key=productKey，TTL=600s， 容量上限
 * 1000（达上限整体清空，参考 access 同款极简有界策略）；<b>fetch 失败 / parse 失败 / null 一律不写缓存</b>，
 * 避免缓存穿透后写入错误值或故障期长期缓存 null。线程安全由 ConcurrentHashMap 保证（并发 miss 允许重复 fetch， 不做
 * single-flight，本阶段不引入 Redis/第三方缓存）。
 * </p>
 */
@Slf4j
public class ThingModelResolver {

	/** 缓存容量上限：达到即整体清空（产品数远小于此，清空代价可忽略） */
	private static final int MAX_ENTRIES = 1000;

	/** 缓存 TTL：600 秒（对齐 access 物模型缓存默认值） */
	private static final long TTL_MILLIS = 600_000L;

	/** L1 缓存条目（不可变）：过期时间戳 + 解析后的物模型快照 */
	private record Entry(ThingModel model, long expireAt) {
	}

	/** 远程获取回调（业务服务绑定各自的 ProductFeignClient） */
	private final ThingModelFetcher fetcher;

	/** 时钟（默认系统时钟；可注入固定/可变时钟用于测试 TTL，不引入第三方框架） */
	private final Clock clock;

	/** L1 本地缓存：productKey → 物模型条目（有界、线程安全） */
	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	/** 使用系统时钟构造 */
	public ThingModelResolver(ThingModelFetcher fetcher) {
		this(fetcher, Clock.systemUTC());
	}

	/** 使用指定时钟构造（测试注入可控时间源） */
	public ThingModelResolver(ThingModelFetcher fetcher, Clock clock) {
		this.fetcher = fetcher;
		this.clock = clock;
	}

	/**
	 * 取产品当前生效物模型（解析后）；无物模型/获取失败/解析失败返回 null（调用方跳过校验放行，不阻塞业务链路）。
	 * @param productKey 产品标识
	 * @return 解析后的 {@link ThingModel}；不可用时为 null
	 */
	public ThingModel resolve(String productKey) {
		if (productKey == null || productKey.isBlank()) {
			return null;
		}
		long now = clock.millis();
		Entry entry = cache.get(productKey);
		if (entry != null && entry.expireAt() > now) {
			return entry.model();
		}
		ThingModel model = fetchAndParse(productKey);
		if (model != null) {
			// 达上限整体清空（极简有界策略），再写入带过期时间的新条目
			if (cache.size() >= MAX_ENTRIES) {
				cache.clear();
			}
			cache.put(productKey, new Entry(model, now + TTL_MILLIS));
		}
		return model;
	}

	/** 远程获取 + 解析；任一步失败返回 null（不抛错，不缓存） */
	private ThingModel fetchAndParse(String productKey) {
		final Result<ThingModelRow> result;
		try {
			result = fetcher.fetch(productKey);
		}
		catch (Exception e) {
			log.warn("[ThingModel] 获取失败 productKey={}", productKey, e);
			return null;
		}
		if (result == null || !result.isSuccess() || result.getData() == null
				|| result.getData().schemaJson() == null) {
			return null;
		}
		try {
			return ThingModelParser.parse(result.getData().schemaJson());
		}
		catch (Exception e) {
			log.warn("[ThingModel] 解析失败 productKey={}", productKey, e);
			return null;
		}
	}

}
