package com.energyx.common.thingmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物模型 Service 入参校验器（M2.1：Command 下发校验核心，纯函数无 I/O）。
 *
 * <p>
 * 复用 {@link ModelValidator} 的类型强转 + specs 深度校验（零复制），补充服务语义：
 * <ul>
 * <li><b>required=true 且参数缺失（含 null）</b> → 校验失败；required=false 缺失 → 允许；</li>
 * <li><b>未定义参数</b>（params 中存在但 Service input 未声明）→ 校验失败（避免设备收到模型未定义的参数）；</li>
 * <li><b>已定义参数</b> → 按定义 dataType/enum/specs 逐项强转+深度校验（含 array/struct/array-of-struct
 * 递归）。</li>
 * </ul>
 * </p>
 */
public final class ThingModelServiceValidator {

	private ThingModelServiceValidator() {
	}

	/** 服务参数校验结果 */
	public record ServiceValidationResult(boolean valid, Map<String, Object> coerced, List<String> errors) {
	}

	/**
	 * 校验服务入参：required 缺失 / 未定义参数 / 参数值深度校验（类型强转 + enum + specs）。
	 * @param service 物模型服务定义（含 input 参数定义）
	 * @param params 指令参数键值对（可空）
	 * @return 校验结果（valid / 强转后参数 / 错误列表，错误含 param 与 reason）
	 */
	public static ServiceValidationResult validateParams(ThingModelService service, Map<String, Object> params) {
		List<String> errors = new ArrayList<>();
		Map<String, Object> coerced = new LinkedHashMap<>();
		if (service == null) {
			return new ServiceValidationResult(false, coerced, List.of("service 为空"));
		}
		List<ThingModelParam> input = service.getInput() == null ? List.of() : service.getInput();
		Map<String, ThingModelParam> defined = new LinkedHashMap<>();
		for (ThingModelParam param : input) {
			defined.put(param.getIdentifier(), param);
		}
		Map<String, Object> safeParams = params == null ? Map.of() : params;

		// 1. required 缺失（含显式 null）
		for (ThingModelParam param : input) {
			if (param.isRequired() && (!safeParams.containsKey(param.getIdentifier())
					|| safeParams.get(param.getIdentifier()) == null)) {
				errors.add("required 参数缺失 param=" + param.getIdentifier());
			}
		}
		// 2. 未定义参数（Service 未声明的参数，拒绝下发避免脏参数）
		for (String key : safeParams.keySet()) {
			if (!defined.containsKey(key)) {
				errors.add("未定义参数 param=" + key);
			}
		}
		// 3. 已定义参数深度校验（复用 ModelValidator 强转 + specs 校验）
		for (Map.Entry<String, Object> entry : safeParams.entrySet()) {
			ThingModelParam param = defined.get(entry.getKey());
			if (param == null) {
				continue; // 未定义参数已在步骤 2 记录
			}
			try {
				coerced.put(entry.getKey(), ModelValidator.coerce(param, entry.getValue()));
			}
			catch (IllegalArgumentException ex) {
				errors.add("param=" + entry.getKey() + " reason=" + ex.getMessage());
			}
		}
		return new ServiceValidationResult(errors.isEmpty(), coerced, errors);
	}

}
