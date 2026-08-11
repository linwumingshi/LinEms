package com.energyx.ems.service;

import com.energyx.common.model.Result;
import com.energyx.ems.client.ShadowFeignClient;
import com.energyx.ems.client.ShadowViewDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 影子 SOC 查询包装（计划初始 SOC 取影子上报，P0-7）。 底层走 Feign（Nacos 服务名 energy-shadow 解析，无硬编码 URL）；
 * 任一步失败（网络/Feign 异常/非 0 code/缺 soc/非法值）返回 empty，由调用方回退包络中点，不阻断计划生成。
 */
@Slf4j
@Component
public class ShadowClient {

	private final ShadowFeignClient feignClient;

	public ShadowClient(ShadowFeignClient feignClient) {
		this.feignClient = feignClient;
	}

	/**
	 * 查询设备影子上报的 SOC（%）。 任一步失败返回 {@link Optional#empty()}，不抛异常（生成链路依赖回退而非硬失败）。
	 * @param deviceId PCS 设备 ID（es_device.iot_device.device_id）
	 * @return 影子 soc 值（clamp 由调用方做）；无影子/无 soc/查询失败 → empty
	 */
	public Optional<Double> reportedSoc(long deviceId) {
		try {
			return parseSoc(feignClient.getShadow(deviceId));
		}
		catch (Exception e) {
			log.warn("查询影子 SOC 失败 deviceId={}: {}", deviceId, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * 解析 shadow 响应 → data.reported.soc（thing-model 属性 soc，float % 0-100）。 任意不满足（code!=0 /
	 * 缺 data.reported / 缺 soc / 非数字 / 非有限 / 负数）→ empty。
	 */
	static Optional<Double> parseSoc(Result<ShadowViewDto> result) {
		if (result == null || !result.isSuccess()) {
			return Optional.empty();
		}
		ShadowViewDto view = result.getData();
		if (view == null || view.getReported() == null) {
			return Optional.empty();
		}
		if (!(view.getReported().get("soc") instanceof Number soc)) {
			return Optional.empty();
		}
		double v = soc.doubleValue();
		if (!Double.isFinite(v) || v < 0) {
			return Optional.empty();
		}
		return Optional.of(v);
	}

}
