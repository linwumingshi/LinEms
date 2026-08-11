package com.energyx.ems.service;

import com.energyx.common.model.Result;
import com.energyx.ems.client.ShadowFeignClient;
import com.energyx.ems.client.ShadowViewDto;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShadowClientTest {

	private static ShadowViewDto viewWithSoc(Object soc) {
		ShadowViewDto view = new ShadowViewDto();
		view.setDeviceId(5001L);
		if (soc != null) {
			view.getReported().put("soc", soc);
		}
		return view;
	}

	// ---------- parseSoc 纯解析（P0-7 影子 SOC 提取契约） ----------

	@Test
	void parseSoc_returnsReportedSocWhenSuccess() {
		assertEquals(Optional.of(30.0), ShadowClient.parseSoc(Result.ok(viewWithSoc(30))));
	}

	@Test
	void parseSoc_emptyWhenNonZeroCode() {
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.fail(500, "boom")));
	}

	@Test
	void parseSoc_emptyWhenNoReportedOrNoSoc() {
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.ok(new ShadowViewDto())));
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.ok(viewWithSoc(null))));
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.ok(null)));
		assertEquals(Optional.empty(), ShadowClient.parseSoc(null));
	}

	@Test
	void parseSoc_emptyWhenSocIllegal() {
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.ok(viewWithSoc("30")))); // 非数字
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.ok(viewWithSoc(-5)))); // 负数
		assertEquals(Optional.empty(), ShadowClient.parseSoc(Result.ok(viewWithSoc(Double.NaN)))); // 非有限
	}

	// ---------- reportedSoc 包装（Feign 失败兜底 empty，不抛异常） ----------

	@Test
	void reportedSoc_delegatesToFeignAndReturnsSoc() {
		ShadowFeignClient feign = mock(ShadowFeignClient.class);
		when(feign.getShadow(5001L)).thenReturn(Result.ok(viewWithSoc(30.0)));
		ShadowClient client = new ShadowClient(feign);
		assertEquals(Optional.of(30.0), client.reportedSoc(5001L));
	}

	@Test
	void reportedSoc_emptyWhenFeignThrows() {
		ShadowFeignClient feign = mock(ShadowFeignClient.class);
		when(feign.getShadow(5001L)).thenThrow(new RuntimeException("shadow down"));
		ShadowClient client = new ShadowClient(feign);
		assertEquals(Optional.empty(), client.reportedSoc(5001L));
	}

}
