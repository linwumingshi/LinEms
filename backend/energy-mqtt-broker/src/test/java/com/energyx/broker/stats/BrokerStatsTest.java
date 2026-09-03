package com.energyx.broker.stats;

import com.energyx.broker.routing.LocalSubscriberIndex;
import com.energyx.broker.session.SessionRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 运行指标计数器快照验证。
 */
class BrokerStatsTest {

	/**
	 * 软拒计数必须进入运维快照，便于监控区分"软拒（可诊断）"与"硬拒（风暴）"。
	 */
	@Test
	void 软拒计数进入快照() {
		BrokerStats stats = new BrokerStats(mock(SessionRegistry.class), mock(LocalSubscriberIndex.class));
		stats.recordAdmissionRedirect();
		assertThat(stats.snapshot()).containsEntry("admissionRedirect", 1L);
	}

}
