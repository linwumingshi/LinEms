package com.energyx.broker.util;

/**
 * MQTT Topic 通配符匹配（MQTT 3.1.1 §4.7 语义）。
 *
 * <ul>
 * <li>+ 匹配单个层级（不跨 /）；</li>
 * <li># 匹配零个或多个层级，必须位于末尾；</li>
 * <li>$share/{group}/ 前缀视为共享订阅：剥掉前缀后再按普通 filter 匹配；</li>
 * <li>$SYS 等以 $ 开头的 topic 不被 # 匹配（MQTT 保留语义，本平台未用）。</li>
 * </ul>
 */
public final class TopicMatcher {

	private TopicMatcher() {
	}

	/**
	 * 判断单个 topic 是否匹配订阅过滤表达式（支持 +/# 通配符与 $share 共享订阅前缀）。
	 * @param topic 待匹配的主题（发布方主题）
	 * @param topicFilter 订阅过滤表达式（可含 +、# 或以 $share/{group}/ 开头）
	 * @return true 匹配成功
	 */
	public static boolean matches(String topic, String topicFilter) {
		if (topic == null || topicFilter == null) {
			return false;
		}
		// 共享订阅：$share/{group}/filter → filter
		String filter = stripSharePrefix(topicFilter);
		if (topic.startsWith("$") && !filter.startsWith("$")) {
			return false;
		}
		return doMatch(topic, filter);
	}

	private static boolean doMatch(String topic, String filter) {
		String[] tLevels = topic.split("/", -1);
		String[] fLevels = filter.split("/", -1);

		int i = 0;
		for (; i < fLevels.length; i++) {
			String f = fLevels[i];
			if (f.equals("#")) {
				// "#" 仅可位于末尾，匹配零个或多个后续层级
				return i == fLevels.length - 1;
			}
			if (i >= tLevels.length) {
				// 过滤表达式层级更深但 topic 已耗尽，不匹配
				return false;
			}
			// 精确层级相等，或 "+" 通配单层级（不跨 "/"）
			if (!f.equals("+") && !f.equals(tLevels[i])) {
				return false;
			}
		}
		return i == tLevels.length;
	}

	/** 剥离 $share/{group}/ 前缀，返回实际过滤表达式；非共享订阅原样返回 */
	public static String stripSharePrefix(String topicFilter) {
		if (topicFilter != null && topicFilter.startsWith("$share/")) {
			// 取出 $share/{group}/ 之后的真实过滤表达式（group 仅用于组内负载均衡，不参与匹配）
			int slash = topicFilter.indexOf('/', "$share/".length());
			if (slash > 0) {
				return topicFilter.substring(slash + 1);
			}
		}
		return topicFilter;
	}

}
