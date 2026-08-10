package com.energyx.shadow.delta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 影子 delta 计算（纯函数）。
 *
 * <p>
 * delta = desired 中与 reported 不一致的属性集合。设备已完成同步的属性 （desired == reported）不进入 delta；此后
 * reported 追平 desired，delta 自然收敛。
 * </p>
 */
public final class DeltaCalculator {

	private DeltaCalculator() {
	}

	/**
	 * 计算 desired 相对 reported 的差异。
	 * @param desired 平台期望（可空）
	 * @param reported 设备当前上报（可空，视为空）
	 * @return 差异属性：identifier → 目标值（保持 desired 顺序）
	 */
	public static Map<String, Object> compute(Map<String, Object> desired, Map<String, Object> reported) {
		Map<String, Object> delta = new LinkedHashMap<>();
		if (desired == null || desired.isEmpty()) {
			return delta;
		}
		for (Map.Entry<String, Object> e : desired.entrySet()) {
			Object reportedValue = reported == null ? null : reported.get(e.getKey());
			if (!Objects.equals(reportedValue, e.getValue())) {
				delta.put(e.getKey(), e.getValue());
			}
		}
		return delta;
	}

	/** 是否还有待同步差异 */
	public static boolean needsSync(Map<String, Object> delta) {
		return delta != null && !delta.isEmpty();
	}

}
