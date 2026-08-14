package com.energyx.rule.engine;

import com.energyx.common.util.ValueCompareUtils;
import com.energyx.rule.model.RuleCondition;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 执行条件求值器（Phase 11 设计 §4.4 / §7.3）。
 *
 * <p>
 * 多条件 AND 短路求值；条件类型：
 * <ul>
 * <li>DEVICE_STATUS：读 Redis {@code iot:online:{deviceId}}（存在=ONLINE）；</li>
 * <li>TIME_RANGE：当前时间在 [start, end] 内（支持跨零点区间）；</li>
 * <li>PROPERTY：优先取触发上下文属性值，其次读影子 {@code iot:shadow:reported:{deviceId}}。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 条件内设备解析：条件自带 device（productKey/deviceName）时经 cache:device:{deviceKey} 解析 deviceId； 未带
 * device 时回退触发上下文设备（触发设备即条件作用设备）。
 * </p>
 */
@Component
public class ConditionEvaluator {

	private static final String ONLINE_KEY_PREFIX = "iot:online:";

	private static final String SHADOW_PREFIX = "iot:shadow:reported:";

	private static final String DEVICE_CACHE_PREFIX = "cache:device:";

	private final StringRedisTemplate redis;

	public ConditionEvaluator(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 全部条件满足才返回 true（空列表=恒真；AND 短路） */
	public boolean evaluate(List<RuleCondition> conditions, RuleContext ctx) {
		if (conditions == null || conditions.isEmpty()) {
			return true;
		}
		for (RuleCondition c : conditions) {
			if (!evaluateOne(c, ctx)) {
				return false;
			}
		}
		return true;
	}

	private boolean evaluateOne(RuleCondition c, RuleContext ctx) {
		if (c == null || c.getType() == null) {
			return false;
		}
		return switch (c.getType()) {
			case "DEVICE_STATUS" -> deviceStatus(c, ctx);
			case "TIME_RANGE" -> timeRange(c);
			case "PROPERTY" -> property(c, ctx);
			default -> false;
		};
	}

	/** 设备在线状态：Redis iot:online:{deviceId} 存在即在线 */
	private boolean deviceStatus(RuleCondition c, RuleContext ctx) {
		Long deviceId = resolveDeviceId(c, ctx);
		if (deviceId == null) {
			return false;
		}
		boolean online = Boolean.TRUE.equals(redis.hasKey(ONLINE_KEY_PREFIX + deviceId));
		return "ONLINE".equals(c.getStatus()) ? online : !online;
	}

	/** 时间范围：支持跨零点（start > end 视为夜间区间） */
	private boolean timeRange(RuleCondition c) {
		if (c.getStart() == null || c.getEnd() == null) {
			return false;
		}
		try {
			LocalTime now = LocalTime.now();
			LocalTime start = LocalTime.parse(c.getStart());
			LocalTime end = LocalTime.parse(c.getEnd());
			if (start.isAfter(end)) {
				// 跨零点：now >= start 或 now <= end
				return !now.isBefore(start) || !now.isAfter(end);
			}
			return !now.isBefore(start) && !now.isAfter(end);
		}
		catch (Exception e) {
			return false;
		}
	}

	/** 属性条件：优先触发上下文，其次影子 */
	private boolean property(RuleCondition c, RuleContext ctx) {
		Object current = ctx.getProperties() == null ? null : ctx.getProperties().get(c.getProperty());
		if (current == null) {
			current = readShadow(c, ctx);
		}
		if (current == null) {
			return false;
		}
		return ValueCompareUtils.compare(c.getOp(), current, c.getValue());
	}

	/** 影子 reported 属性读取（Hash 字段） */
	private Object readShadow(RuleCondition c, RuleContext ctx) {
		Long deviceId = resolveDeviceId(c, ctx);
		if (deviceId == null) {
			return null;
		}
		try {
			Map<Object, Object> shadow = redis.opsForHash().entries(SHADOW_PREFIX + deviceId);
			return shadow.get(c.getProperty());
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * 条件内设备 ID 解析：条件自带 device 且与触发设备不同 → 读 cache:device:{deviceKey}； 未带 device 或与触发设备一致 →
	 * 直接用触发上下文设备 ID。
	 */
	private Long resolveDeviceId(RuleCondition c, RuleContext ctx) {
		if (ctx.getDeviceId() == null) {
			return null;
		}
		if (c.getDevice() == null || c.getDevice().getProductKey() == null) {
			return ctx.getDeviceId();
		}
		// 条件设备与触发设备同一产品同名 → 视为同设备
		if (c.getDevice().getProductKey().equals(ctx.getProductKey())
				&& (c.getDevice().getDeviceName() == null || c.getDevice().getDeviceName().isBlank()
						|| c.getDevice().getDeviceName().equals(ctx.getDeviceName()))) {
			return ctx.getDeviceId();
		}
		// 不同设备：走 cache:device 缓存解析（JSON：deviceId 字段）
		String deviceKey = c.getDevice().getProductKey() + "_" + c.getDevice().getDeviceName();
		try {
			String json = redis.opsForValue().get(DEVICE_CACHE_PREFIX + deviceKey);
			if (json == null) {
				return null;
			}
			// 轻量解析 deviceId（避免引入额外依赖），JSON 形如 {"deviceId":123,...}
			int idx = json.indexOf("\"deviceId\"");
			if (idx < 0) {
				idx = json.indexOf("deviceId");
			}
			if (idx < 0) {
				return null;
			}
			String tail = json.substring(idx + "deviceId".length());
			int colon = tail.indexOf(':');
			if (colon < 0) {
				return null;
			}
			StringBuilder num = new StringBuilder();
			for (int i = colon + 1; i < tail.length() && Character.isDigit(tail.charAt(i)); i++) {
				num.append(tail.charAt(i));
			}
			return num.length() == 0 ? null : Long.parseLong(num.toString());
		}
		catch (Exception e) {
			return null;
		}
	}

}
