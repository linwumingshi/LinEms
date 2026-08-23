package com.energyx.common.thingmodel;

import com.energyx.common.model.Result;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThingModelResolver 测试（M2.4）：缓存命中/回源/失败不缓存/TTL/容量/并发。
 *
 * <p>
 * 使用可前进的 {@link MutableClock} 测试 TTL，不依赖真实等待；fetcher 用 lambda + 计数器， 不引入 Mockito 之外的框架。
 * </p>
 */
class ThingModelResolverTest {

	private static final String SCHEMA = """
			{"properties":[{"identifier":"soc","name":"荷电状态","dataType":"float","accessMode":"r"}]}
			""";

	/** 可前进时钟（测试 TTL 用） */
	static final class MutableClock extends Clock {

		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(long millis) {
			instant = instant.plusMillis(millis);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

	private record Fixture(ThingModelResolver resolver, AtomicInteger fetchCount, MutableClock clock) {
	}

	private static Fixture fixture(ThingModelFetcher fetcher) {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000_000L));
		AtomicInteger count = new AtomicInteger();
		ThingModelResolver resolver = new ThingModelResolver(key -> {
			count.incrementAndGet();
			return fetcher.fetch(key);
		}, clock);
		return new Fixture(resolver, count, clock);
	}

	private static ThingModelFetcher okFetcher() {
		return pk -> Result.ok(new ThingModelRow(1L, 1L, "V1.0", SCHEMA, 1));
	}

	private static ThingModelFetcher nullResultFetcher() {
		return pk -> null;
	}

	private static ThingModelFetcher failFetcher() {
		return pk -> Result.fail(400, "no model");
	}

	private static ThingModelFetcher throwingFetcher() {
		return pk -> {
			throw new IllegalStateException("feign down");
		};
	}

	private static ThingModelFetcher badJsonFetcher() {
		return pk -> Result.ok(new ThingModelRow(1L, 1L, "V1.0", "{not-json", 1));
	}

	private static ThingModelFetcher emptySchemaFetcher() {
		return pk -> Result.ok(new ThingModelRow(1L, 1L, "V1.0", null, 1));
	}

	@Test
	void cacheMiss_fetchesParsesAndCaches() {
		Fixture f = fixture(okFetcher());
		ThingModel model = f.resolver().resolve("pk-1");
		assertNotNull(model);
		assertNotNull(model.getProperties().get("soc"));
		assertEquals(1, f.fetchCount().get());
	}

	@Test
	void secondResolve_cacheHit_noRefetch() {
		Fixture f = fixture(okFetcher());
		assertNotNull(f.resolver().resolve("pk-1"));
		assertNotNull(f.resolver().resolve("pk-1"));
		assertEquals(1, f.fetchCount().get());
	}

	@Test
	void differentKeys_fetchSeparately() {
		Fixture f = fixture(okFetcher());
		assertNotNull(f.resolver().resolve("pk-a"));
		assertNotNull(f.resolver().resolve("pk-b"));
		assertEquals(2, f.fetchCount().get());
	}

	@Test
	void successSchema_returnsModel() {
		Fixture f = fixture(okFetcher());
		ThingModel model = f.resolver().resolve("pk-1");
		assertNotNull(model);
		assertEquals("soc", model.getProperties().get("soc").getIdentifier());
	}

	@Test
	void resultNull_returnsNull() {
		Fixture f = fixture(nullResultFetcher());
		assertNull(f.resolver().resolve("pk-1"));
	}

	@Test
	void resultFail_returnsNull() {
		Fixture f = fixture(failFetcher());
		assertNull(f.resolver().resolve("pk-1"));
	}

	@Test
	void fetcherThrows_returnsNull() {
		Fixture f = fixture(throwingFetcher());
		assertNull(f.resolver().resolve("pk-1"));
	}

	@Test
	void schemaJsonNullOrEmpty_returnsNull() {
		Fixture f = fixture(emptySchemaFetcher());
		assertNull(f.resolver().resolve("pk-1"));
	}

	@Test
	void invalidJson_returnsNull() {
		Fixture f = fixture(badJsonFetcher());
		assertNull(f.resolver().resolve("pk-1"));
	}

	@Test
	void blankProductKey_returnsNullWithoutFetch() {
		Fixture f = fixture(okFetcher());
		assertNull(f.resolver().resolve(""));
		assertNull(f.resolver().resolve(null));
		assertEquals(0, f.fetchCount().get());
	}

	@Test
	void parseFail_secondCallRefetches() {
		Fixture f = fixture(badJsonFetcher());
		assertNull(f.resolver().resolve("pk-1"));
		assertNull(f.resolver().resolve("pk-1"));
		assertEquals(2, f.fetchCount().get()); // parse 失败不缓存 → 每次重新 fetch
	}

	@Test
	void fetchFail_secondCallRefetches() {
		Fixture f = fixture(failFetcher());
		assertNull(f.resolver().resolve("pk-1"));
		assertNull(f.resolver().resolve("pk-1"));
		assertEquals(2, f.fetchCount().get()); // fetch 失败不缓存 → 每次重新 fetch
	}

	@Test
	void ttlNotExpired_cacheHit() {
		Fixture f = fixture(okFetcher());
		assertNotNull(f.resolver().resolve("pk-1"));
		f.clock().advance(599_999L);
		assertNotNull(f.resolver().resolve("pk-1"));
		assertEquals(1, f.fetchCount().get());
	}

	@Test
	void ttlExpired_refetch() {
		Fixture f = fixture(okFetcher());
		assertNotNull(f.resolver().resolve("pk-1"));
		f.clock().advance(600_000L);
		assertNotNull(f.resolver().resolve("pk-1"));
		assertEquals(2, f.fetchCount().get());
	}

	@Test
	void capacityLimit_clearsAndRefetchesOldKeys() {
		Fixture f = fixture(okFetcher());
		for (int i = 1; i <= 1000; i++) {
			assertNotNull(f.resolver().resolve("pk-" + i));
		}
		assertEquals(1000, f.fetchCount().get());
		// 第 1001 个触发整体清空
		assertNotNull(f.resolver().resolve("pk-1001"));
		assertEquals(1001, f.fetchCount().get());
		// 清空后旧 key 需重新 fetch
		assertNotNull(f.resolver().resolve("pk-1"));
		assertEquals(1002, f.fetchCount().get());
	}

	@Test
	void nullResult_notCached() {
		Fixture f = fixture(nullResultFetcher());
		assertNull(f.resolver().resolve("pk-1"));
		assertNull(f.resolver().resolve("pk-1"));
		assertEquals(2, f.fetchCount().get()); // null 不写缓存 → 每次重新 fetch
	}

	@Test
	void concurrentResolve_threadSafe() throws Exception {
		Fixture f = fixture(okFetcher());
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<ThingModel>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				return f.resolver().resolve("pk-1");
			}));
		}
		start.countDown();
		for (Future<ThingModel> future : futures) {
			ThingModel model = future.get();
			assertNotNull(model);
			assertTrue(model.getProperties().containsKey("soc"));
		}
		pool.shutdown();
		// 并发 miss 不做 single-flight：fetch 次数 ≥1（可能多次），无数据竞争/异常即可
		assertTrue(f.fetchCount().get() >= 1);
	}

}
