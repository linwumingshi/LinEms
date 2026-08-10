package com.energyx.common.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作封装（JSON 序列化），key 规范见 docs/design/Redis-key规范.md。
 */
@Slf4j
@Component
public class RedisUtils {

	private final StringRedisTemplate redis;

	private final ObjectMapper objectMapper;

	public RedisUtils(StringRedisTemplate redis, ObjectMapper objectMapper) {
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	// ---------- String / JSON ----------

	public void set(String key, String value, Duration ttl) {
		redis.opsForValue().set(key, value, ttl);
	}

	public void set(String key, String value) {
		redis.opsForValue().set(key, value);
	}

	public String get(String key) {
		return redis.opsForValue().get(key);
	}

	public void setJson(String key, Object value, Duration ttl) {
		try {
			redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
		}
		catch (Exception e) {
			throw new IllegalStateException("serialize value error: " + e.getMessage(), e);
		}
	}

	public <T> T getJson(String key, Class<T> clazz) {
		String json = get(key);
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, clazz);
		}
		catch (Exception e) {
			log.warn("deserialize redis value error, key={}", key, e);
			return null;
		}
	}

	public <T> T getJson(String key, TypeReference<T> typeReference) {
		String json = get(key);
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, typeReference);
		}
		catch (Exception e) {
			log.warn("deserialize redis value error, key={}", key, e);
			return null;
		}
	}

	// ---------- key 操作 ----------

	public boolean delete(String key) {
		return Boolean.TRUE.equals(redis.delete(key));
	}

	public boolean expire(String key, long seconds) {
		return Boolean.TRUE.equals(redis.expire(key, seconds, TimeUnit.SECONDS));
	}

	public boolean hasKey(String key) {
		return Boolean.TRUE.equals(redis.hasKey(key));
	}

	public Set<String> keys(String pattern) {
		return redis.keys(pattern);
	}

	// ---------- 原子操作 ----------

	/** SETNX：仅当不存在时写入 */
	public boolean setNx(String key, String value, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl));
	}

	public Long incr(String key) {
		return redis.opsForValue().increment(key);
	}

	// ---------- Hash ----------

	public void hSet(String key, String field, String value) {
		redis.opsForHash().put(key, field, value);
	}

	public void hSetAll(String key, Map<String, String> map) {
		redis.opsForHash().putAll(key, map);
	}

	public String hGet(String key, String field) {
		Object value = redis.opsForHash().get(key, field);
		return value == null ? null : value.toString();
	}

	public Map<Object, Object> hGetAll(String key) {
		return redis.opsForHash().entries(key);
	}

	public boolean hDel(String key, Object... fields) {
		return redis.opsForHash().delete(key, fields) > 0;
	}

	// ---------- List ----------

	public long lPush(String key, String... values) {
		return redis.opsForList().leftPushAll(key, values);
	}

	public String rPop(String key) {
		return redis.opsForList().rightPop(key);
	}

	public List<String> lRange(String key, long start, long end) {
		return redis.opsForList().range(key, start, end);
	}

	public long lSize(String key) {
		Long size = redis.opsForList().size(key);
		return size == null ? 0 : size;
	}

}
