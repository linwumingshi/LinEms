package com.energyx.common.thingmodel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 物模型 desired 校验器（M2.2：Shadow desired 写入校验核心，纯函数无 I/O）。
 *
 * <p>
 * 校验规则（逐属性，复用 {@link ModelValidator}，零复制）：
 * <ul>
 * <li><b>存在性</b>：desired 中的 identifier 必须存在于 ThingModel.properties，否则拒绝；</li>
 * <li><b>可写性</b>：accessMode 必须为 w / rw，r（含未声明按 parser 默认 r）拒绝写入；</li>
 * <li><b>深度校验</b>：调用 {@link ModelValidator#coerce(ThingModelProperty, Object)} 执行
 * dataType/enum/min/max/step/length/struct/array/array-of-struct 校验与强转。</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>语义边界</b>：desired 是部分属性更新——只校验请求中实际出现的属性；required 不在此强制 （desired 中未出现的 required
 * 属性不得报错）；null 值按 {@link ModelValidator} 既有语义处理 （coerce(null)=null 合法），不定义第二套 null 规则。
 * </p>
 */
public final class ThingModelDesiredValidator {

	private ThingModelDesiredValidator() {
	}

	/** desired 校验结果（errors 含 property 与 reason，不含 desired 原始值） */
	public record DesiredValidationResult(boolean valid, List<String> errors) {
	}

	/**
	 * 校验整个 desired Map（原子语义：任一属性非法即 overall invalid，调用方据此拒绝整体写入）。
	 * @param model 产品物模型（null 视为无模型，全部属性判为不存在）
	 * @param desired 期望属性键值对（可空）
	 * @return 校验结果（valid=false 时 errors 含全部非法属性定位）
	 */
	public static DesiredValidationResult validateDesired(ThingModel model, Map<String, Object> desired) {
		List<String> errors = new ArrayList<>();
		if (desired == null || desired.isEmpty()) {
			return new DesiredValidationResult(true, errors);
		}
		for (Map.Entry<String, Object> entry : desired.entrySet()) {
			String identifier = entry.getKey();
			ThingModelProperty prop = model == null ? null : model.getProperties().get(identifier);
			if (prop == null) {
				errors.add("property=" + identifier + " reason=属性不存在于物模型");
				continue;
			}
			if (!isWritable(prop)) {
				errors.add("property=" + identifier + " reason=只读属性不可写 accessMode=" + prop.getAccessMode());
				continue;
			}
			try {
				// 复用 M1/M2.1 类型强转 + specs 深度校验（含
				// enum/min/max/step/length/struct/array/array-of-struct）
				ModelValidator.coerce(prop, entry.getValue());
			}
			catch (IllegalArgumentException ex) {
				errors.add("property=" + identifier + " reason=" + ex.getMessage());
			}
		}
		return new DesiredValidationResult(errors.isEmpty(), errors);
	}

	/**
	 * 可写性判定：w / rw 允许写；r 拒绝。null（直接构造未赋值）按 parser 默认 r 处理（安全默认）。
	 */
	private static boolean isWritable(ThingModelProperty prop) {
		String mode = prop.getAccessMode();
		return "w".equals(mode) || "rw".equals(mode);
	}

}
