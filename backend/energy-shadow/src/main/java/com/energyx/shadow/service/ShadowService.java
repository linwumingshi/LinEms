package com.energyx.shadow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.shadow.config.ShadowProperties;
import com.energyx.shadow.delta.DeltaCalculator;
import com.energyx.shadow.delta.ShadowDeltaPublisher;
import com.energyx.shadow.mapper.ShadowHistoryMapper;
import com.energyx.shadow.mapper.ShadowMapper;
import com.energyx.shadow.model.ShadowRow;
import com.energyx.shadow.util.ShadowRedisKeys;
import com.energyx.shadow.web.dto.ShadowView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子核心服务：reported/desired 双写（Redis 热路径 + MySQL 版本乐观锁）。
 *
 * <p>
 * <b>幂等性设计</b>：影子合并是<b>天然幂等</b>的操作——同一属性快照重复合并结果不变， 因此消费端不做消息级去重（避免"部分失败后重放被去重跳过导致 MySQL
 * 永不收敛"）， 依赖 Kafka at-least-once 重放 + 乐观锁重试自我收敛。desired 由版本乐观锁防并发覆盖。
 * </p>
 */
@Slf4j
@Service
public class ShadowService {

	/** 操作类型：1设备上报 2平台设置（对齐 iot_shadow_history） */
	private static final int OP_REPORTED = 1;

	private static final int OP_DESIRED = 2;

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ShadowMapper shadowMapper;

	private final ShadowHistoryMapper historyMapper;

	private final StringRedisTemplate redis;

	private final ShadowDeltaPublisher deltaPublisher;

	private final ObjectMapper objectMapper;

	private final ShadowProperties props;

	public ShadowService(ShadowMapper shadowMapper, ShadowHistoryMapper historyMapper, StringRedisTemplate redis,
			ShadowDeltaPublisher deltaPublisher, ObjectMapper objectMapper, ShadowProperties props) {
		this.shadowMapper = shadowMapper;
		this.historyMapper = historyMapper;
		this.redis = redis;
		this.deltaPublisher = deltaPublisher;
		this.objectMapper = objectMapper;
		this.props = props;
	}

	/** reported 变更结果 */
	public record ReportedResult(boolean changed, String reportedJson, int version) {
		static ReportedResult unchanged() {
			return new ReportedResult(false, null, 0);
		}
	}

	/** desired 设置结果：含最终写入的期望值集合，以及相对当前 reported 的差异集合（已发布 iot-shadow-delta） */
	public record DesiredResult(
			/** 最终写入的期望值集合，键为属性标识、值为期望值 */
			Map<String, Object> desired,
			/** 相对当前 reported 的差异集合（供设备同步）；无差异时为空快照 */
			Map<String, Object> delta) {
	}

	/**
	 * 设备属性上报 → 影子 reported 双写。 合并语义：新上报的属性覆盖旧值，未上报的旧属性保留（部分上报不丢失上下文）。
	 */
	public void applyReported(long deviceId, long tenantId, Map<String, Object> properties) {
		if (properties == null || properties.isEmpty()) {
			return;
		}
		ReportedResult result = upsertReported(deviceId, tenantId, properties);
		writeReportedRedis(deviceId, properties);
		if (result.changed()) {
			maybeWriteHistory(deviceId, result.reportedJson(), result.version(), OP_REPORTED);
		}
	}

	/**
	 * 平台设置期望 → 影子 desired 双写 + delta 检测发布。
	 * @return desired 与差异集合（已发布 iot-shadow-delta）
	 */
	public DesiredResult setDesired(long deviceId, long tenantId, Map<String, Object> desired) {
		Map<String, Object> target = new LinkedHashMap<>(desired == null ? Map.of() : desired);
		if (target.isEmpty()) {
			return new DesiredResult(target, Map.of());
		}
		upsertDesired(deviceId, tenantId, target);
		writeDesiredRedis(deviceId, target);

		Map<String, Object> reported = readReportedRedis(deviceId);
		Map<String, Object> delta = DeltaCalculator.compute(target, reported);
		if (DeltaCalculator.needsSync(delta)) {
			ShadowRow row = shadowMapper.selectById(deviceId);
			int version = row == null ? 0 : (row.getVersion() == null ? 0 : row.getVersion());
			deltaPublisher.publish(deviceId, tenantId, version, delta);
			maybeWriteHistory(deviceId, toJson(target), version, OP_DESIRED);
		}
		return new DesiredResult(target, delta);
	}

	/** 影子合并视图：Redis 热路径优先，未命中回 MySQL；last_reported_time 仅存于 MySQL 行 */
	public ShadowView getShadow(long deviceId) {
		ShadowView view = new ShadowView();
		view.setDeviceId(deviceId);
		Map<String, Object> reported = readReportedRedis(deviceId);
		Map<String, Object> desired = readDesiredRedis(deviceId);
		Integer version = null;
		LocalDateTime lastReportedTime = null;
		if (reported.isEmpty() || desired.isEmpty()) {
			ShadowRow row = shadowMapper.selectById(deviceId);
			if (row != null) {
				if (reported.isEmpty()) {
					reported = parse(row.getReported());
				}
				if (desired.isEmpty()) {
					desired = parse(row.getDesired());
				}
				version = row.getVersion();
				lastReportedTime = row.getLastReportedTime();
			}
		}
		else {
			// Redis 热路径：reported/desired 齐备但缓存不含时间字段 → 补一次主键查询（PK 命中，管理端可接受）
			ShadowRow row = shadowMapper.selectById(deviceId);
			if (row != null) {
				lastReportedTime = row.getLastReportedTime();
			}
		}
		view.setReported(reported);
		view.setDesired(desired);
		view.setVersion(version);
		view.setLastReportedTime(
				lastReportedTime == null ? null : lastReportedTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		return view;
	}

	// ------------------------------------------------------------------
	// 私有实现
	// ------------------------------------------------------------------

	private ReportedResult upsertReported(long deviceId, long tenantId, Map<String, Object> properties) {
		int attempt = 0;
		while (attempt++ < props.getOptimisticMaxRetry()) {
			ShadowRow row = shadowMapper.selectById(deviceId);
			if (row == null) {
				String json = toJson(properties);
				int n = shadowMapper.insertReported(deviceId, tenantId, json, LocalDateTime.now());
				if (n > 0) {
					return new ReportedResult(true, json, 1);
				}
				continue; // 并发插入竞争（其他线程已建行），重试走更新
			}
			Map<String, Object> merged = merge(parse(row.getReported()), properties);
			String mergedJson = toJson(merged);
			if (mergedJson.equals(row.getReported())) {
				return ReportedResult.unchanged(); // 值无变化，跳过写库与历史
			}
			int updated = shadowMapper.updateReported(deviceId, mergedJson, row.getVersion(), LocalDateTime.now());
			if (updated > 0) {
				return new ReportedResult(true, mergedJson, row.getVersion() + 1);
			}
			// 版本冲突（其他线程先更新）→ 重试读取最新
		}
		throw new IllegalStateException("影子 reported 乐观锁重试耗尽 deviceId=" + deviceId);
	}

	private void upsertDesired(long deviceId, long tenantId, Map<String, Object> desired) {
		int attempt = 0;
		String desiredJson = toJson(desired);
		while (attempt++ < props.getOptimisticMaxRetry()) {
			ShadowRow row = shadowMapper.selectById(deviceId);
			if (row == null) {
				int n = shadowMapper.insertDesired(deviceId, tenantId, desiredJson, LocalDateTime.now());
				if (n > 0) {
					return;
				}
				continue;
			}
			int updated = shadowMapper.updateDesired(deviceId, desiredJson, row.getVersion(), LocalDateTime.now());
			if (updated > 0) {
				return;
			}
		}
		throw new IllegalStateException("影子 desired 乐观锁重试耗尽 deviceId=" + deviceId);
	}

	private void maybeWriteHistory(long deviceId, String snapshot, int version, int operatorType) {
		if (!props.isHistoryEnabled()) {
			return;
		}
		Boolean acquired = redis.opsForValue()
			.setIfAbsent(ShadowRedisKeys.historyThrottle(deviceId), "1",
					Duration.ofSeconds(props.getHistoryThrottleSeconds()));
		if (!Boolean.TRUE.equals(acquired)) {
			return; // 节流：每设备每分钟至多一条历史
		}
		try {
			historyMapper.insert(deviceId, version, snapshot, operatorType);
		}
		catch (Exception e) {
			log.warn("[Shadow] 变更历史写入失败 deviceId={} version={}", deviceId, version, e);
		}
	}

	private void writeReportedRedis(long deviceId, Map<String, Object> properties) {
		String key = ShadowRedisKeys.reported(deviceId);
		for (Map.Entry<String, Object> e : properties.entrySet()) {
			redis.opsForHash().put(key, e.getKey(), toJson(e.getValue()));
		}
		redis.expire(key, Duration.ofDays(props.getReportedTtlDays()));
	}

	private void writeDesiredRedis(long deviceId, Map<String, Object> desired) {
		String key = ShadowRedisKeys.desired(deviceId);
		for (Map.Entry<String, Object> e : desired.entrySet()) {
			redis.opsForHash().put(key, e.getKey(), toJson(e.getValue()));
		}
		redis.expire(key, Duration.ofDays(props.getReportedTtlDays()));
	}

	private Map<String, Object> readReportedRedis(long deviceId) {
		return readHashAsMap(ShadowRedisKeys.reported(deviceId));
	}

	private Map<String, Object> readDesiredRedis(long deviceId) {
		return readHashAsMap(ShadowRedisKeys.desired(deviceId));
	}

	private Map<String, Object> readHashAsMap(String key) {
		// Spring Data Redis 3.x 的 opsForHash() 泛型签名为 <K,Object,Object>，entries 返回
		// Map<Object,Object>
		Map<Object, Object> hash = redis.opsForHash().entries(key);
		if (hash == null || hash.isEmpty()) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> e : hash.entrySet()) {
			// Redis Hash 每字段存的是属性的 JSON 值（标量/数组/对象），按 Object 解析回属性值
			result.put(String.valueOf(e.getKey()), parseValue(String.valueOf(e.getValue())));
		}
		return result;
	}

	/** 解析单属性 JSON 值（区别于 parse 解析整对象），失败时原样返回字符串避免丢值 */
	private Object parseValue(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(json, Object.class);
		}
		catch (Exception e) {
			log.warn("[Shadow] 属性值 JSON 解析失败 json={}", json, e);
			return json;
		}
	}

	/** 合并：旧值 + 新上报，新值覆盖旧值（保留未上报属性） */
	private Map<String, Object> merge(Map<String, Object> old, Map<String, Object> fresh) {
		Map<String, Object> merged = new LinkedHashMap<>();
		if (old != null) {
			merged.putAll(old);
		}
		if (fresh != null) {
			merged.putAll(fresh);
		}
		return merged;
	}

	private Map<String, Object> parse(String json) {
		if (json == null || json.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (Exception e) {
			log.warn("[Shadow] 影子 JSON 解析失败 json={}", json, e);
			return new LinkedHashMap<>();
		}
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception e) {
			throw new IllegalStateException("影子 JSON 序列化失败: " + value, e);
		}
	}

}
