package com.energyx.rule.engine;

import com.energyx.common.util.ValueCompareUtils;
import com.energyx.rule.model.RuleDevice;
import com.energyx.rule.model.RuleTrigger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 触发器匹配器（Phase 11 设计 §4.3 / §7.2）。
 *
 * <p>
 * 多触发器 OR：任一命中即返回 true。类型语义：
 * <ul>
 * <li>PROPERTY：设备命中且属性值 op 比较成立（数值优先，复用 {@link ValueCompareUtils}）；</li>
 * <li>TIMER：由 xxl-job 调度直接驱动（本类不判 cron，走 scheduler 通道）；</li>
 * <li>LIFECYCLE：上下文 eventType 与配置 event 相等；</li>
 * <li>ALARM：上下文告警 code/level/state 匹配；</li>
 * <li>MANUAL：由手动触发 API 直接驱动。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 设备命中语义：产品不匹配直接 false；deviceName 为空=产品级（产品下全部设备命中）； deviceName 非空时经
 * cache:device:{deviceKey} 解析 deviceId 与触发设备比对（属性消息只有 deviceId， 不携带 deviceName，故需反向解析）。
 * </p>
 */
@Component
public class TriggerMatcher {

	private static final String DEVICE_CACHE_PREFIX = "cache:device:";

	private final StringRedisTemplate redis;

	public TriggerMatcher(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 判断规则是否被当前上下文触发（OR 语义）。
	 * @param trigger 规则中的单个触发器
	 * @param ctx 触发上下文
	 * @return true 命中
	 */
	public boolean matches(RuleTrigger trigger, RuleContext ctx) {
		if (trigger == null || trigger.getType() == null || ctx == null) {
			return false;
		}
		return switch (trigger.getType()) {
			case "PROPERTY" -> matchProperty(trigger, ctx);
			case "LIFECYCLE" -> matchLifecycle(trigger, ctx);
			case "ALARM" -> matchAlarm(trigger, ctx);
			// TIMER/MANUAL 由调度中心 / 手动 API 直接驱动，不在此判断
			default -> false;
		};
	}

	private boolean matchProperty(RuleTrigger trigger, RuleContext ctx) {
		if (ctx.getDeviceId() == null || trigger.getDevice() == null
				|| !deviceHit(trigger.getDevice(), ctx.getProductKey(), ctx.getDeviceId())) {
			return false;
		}
		Object current = ctx.getProperties() == null ? null : ctx.getProperties().get(trigger.getProperty());
		if (current == null) {
			return false;
		}
		return ValueCompareUtils.compare(trigger.getOp(), current, trigger.getValue());
	}

	private boolean matchLifecycle(RuleTrigger trigger, RuleContext ctx) {
		if (trigger.getEvent() == null) {
			return false;
		}
		if (trigger.getDevice() != null && trigger.getDevice().getProductKey() != null
				&& !deviceHit(trigger.getDevice(), ctx.getProductKey(), ctx.getDeviceId())) {
			return false;
		}
		return trigger.getEvent().equals(ctx.getLifecycleEvent());
	}

	private boolean matchAlarm(RuleTrigger trigger, RuleContext ctx) {
		Object code = ctx.getAlarm().get("code");
		Object level = ctx.getAlarm().get("level");
		Object state = ctx.getAlarm().get("state");
		if (trigger.getAlarmCode() != null && !trigger.getAlarmCode().equals(code)) {
			return false;
		}
		if (trigger.getLevel() != null && !trigger.getLevel().equals(level)) {
			return false;
		}
		return trigger.getState() == null || trigger.getState().equals(state);
	}

	/**
	 * 设备命中判定。
	 * @param device 规则中的设备引用（productKey + deviceName 可空）
	 * @param productKey 触发设备产品
	 * @param deviceId 触发设备 ID
	 */
	private boolean deviceHit(RuleDevice device, String productKey, Long deviceId) {
		if (!device.getProductKey().equals(productKey)) {
			return false;
		}
		if (device.getDeviceName() == null || device.getDeviceName().isBlank()) {
			// 产品级：产品下全部设备命中
			return true;
		}
		Long ruleDeviceId = resolveDeviceId(device.getProductKey(), device.getDeviceName());
		return ruleDeviceId != null && ruleDeviceId.equals(deviceId);
	}

	/** 经 cache:device:{productKey}_{deviceName} 缓存解析 deviceId（JSON 含 deviceId 字段） */
	private Long resolveDeviceId(String productKey, String deviceName) {
		try {
			String json = redis.opsForValue().get(DEVICE_CACHE_PREFIX + productKey + "_" + deviceName);
			if (json == null) {
				return null;
			}
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
