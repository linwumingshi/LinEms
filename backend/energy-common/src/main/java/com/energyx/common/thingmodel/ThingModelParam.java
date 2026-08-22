package com.energyx.common.thingmodel;

import lombok.Data;

import java.util.List;

/**
 * 服务入参/出参定义项（也可作为 struct 结构体字段定义）。
 *
 * <p>
 * M1 起支持 specs 深度校验字段（min/max/length/elementType/size/structFields）； M2.1 起补充
 * required（服务入参必填）与 enumValues（参数枚举，顶层优先/specs.enumValues 兜底）， 供 Command 下发校验 与 struct
 * 字段递归校验（{@link ModelValidator}）使用。
 * </p>
 */
@Data
public class ThingModelParam {

	/** 参数标识符 */
	private String identifier;

	/** 参数数据类型（同属性 dataType 取值） */
	private String dataType;

	/** 参数单位（仅展示） */
	private String unit;

	/** 参数是否必填（服务入参校验；struct 字段语义上恒必填，缺字段即拒绝） */
	private boolean required;

	/** dataType=enum 时的枚举定义（顶层 enumValues 优先，specs.enumValues 兜底） */
	private List<EnumValue> enumValues;

	/** specs.min 最小值（数值类型）；null=未定义不校验 */
	private Double min;

	/** specs.max 最大值（数值类型）；null=未定义不校验 */
	private Double max;

	/** specs.length 字符串最大长度（string/text/date/time）；null=未定义不校验 */
	private Integer length;

	/** specs.elementType 数组元素类型（array）；null=未定义不校验 */
	private String elementType;

	/** specs.size 数组最大元素个数（array）；null=未定义不校验 */
	private Integer size;

	/** specs.structFields 结构体字段定义（struct）；null=未定义时仅要求对象 */
	private List<ThingModelParam> structFields;

}
