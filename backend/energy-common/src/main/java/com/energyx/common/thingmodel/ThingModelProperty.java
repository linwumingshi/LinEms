package com.energyx.common.thingmodel;

import lombok.Data;

import java.util.List;

/**
 * 物模型属性定义（对应 schema_json.properties[]）。
 *
 * <p>
 * specs 深度校验字段（M1 物模型契约统一）：min/max/step/length/elementType/size/structFields 由
 * {@link ThingModelParser} 从 specs 对象解析，供 {@link ModelValidator} 做范围/长度/结构/数组校验； 历史
 * schema 无 specs 时这些字段为 null，校验保持原行为。
 * </p>
 */
@Data
public class ThingModelProperty {

	/** 属性标识符（TDengine 宽表列名 / 影子字段名） */
	private String identifier;

	/** 属性中文名（展示用，无业务校验语义） */
	private String name;

	/** float/int/bool/enum/string/struct/array */
	private String dataType;

	/** 单位（如 ℃/kWh/A，仅展示，不参与校验） */
	private String unit;

	/** r（只读上报）/ rw（可读可写）/ w（只写） */
	private String accessMode;

	/** required=true 表示应上报（部分上报不强制全量，缺失仅告警不拒绝） */
	private boolean required;

	/** dataType=enum 时的枚举定义（唯一来源：顶层 enumValues 优先，specs.enumValues 兜底） */
	private List<EnumValue> enumValues;

	/** specs.min 最小值（数值类型，如 0）；null=未定义不校验 */
	private Double min;

	/** specs.max 最大值（数值类型，如 100）；null=未定义不校验 */
	private Double max;

	/** specs.step 步长（数值类型，相对 min 的增量）；null=未定义不校验 */
	private Double step;

	/** specs.length 字符串最大长度（string/text/date/time）；null=未定义不校验 */
	private Integer length;

	/** specs.elementType 数组元素类型（array，如 int/text/struct）；null=未定义不校验 */
	private String elementType;

	/** specs.size 数组最大元素个数（array）；null=未定义不校验 */
	private Integer size;

	/** specs.structFields 结构体字段定义（struct）；null=未定义时仅要求对象 */
	private List<ThingModelParam> structFields;

}
