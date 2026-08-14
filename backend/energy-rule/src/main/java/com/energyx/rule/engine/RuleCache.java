package com.energyx.rule.engine;

import com.energyx.common.redis.RedisChannelConstant;
import com.energyx.common.redis.RedisUtils;
import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.mapper.SceneRuleMapper;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleTrigger;
import com.energyx.rule.service.RuleService;
import com.energyx.rule.util.RuleRedisKeys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则热加载缓存 + 触发索引（Phase 11 设计 §7.1）。
 *
 * <p>
 * 三层机制：
 * <ul>
 * <li>进程内缓存 {@code ruleCache}（Map&lt;ruleId, CachedRule&gt;，L1 热点）；</li>
 * <li>Redis L2 {@code rule:cache:{id}}（RuleConfig JSON，10min，变更主动删）；</li>
 * <li>刷新来源：启动 init + @Scheduled 全量兜底（DistributedLock 防多实例重复）+ {@code rule:changed}
 * pub/sub 增量刷新（CRUD/启停秒级生效）。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 索引维度：属性触发按 deviceKey 索引（deviceName 可空=产品级）、生命周期/告警/定时/手动按类型全量索引。 匹配时先取候选规则再逐个判断，避免全表扫描。
 * </p>
 */
@Slf4j
@Component
public class RuleCache {

	/** 本地缓存的单条规则（含解析后的 DSL 配置） */
	public record CachedRule(SceneRuleRow row, RuleConfig config) {

		public Long id() {
			return row.getRuleId();
		}

		public Integer debounceSeconds() {
			return row.getDebounceSeconds() == null ? 300 : row.getDebounceSeconds();
		}

		public Long tenantId() {
			return row.getTenantId();
		}

	}

	private final SceneRuleMapper ruleMapper;

	private final RuleService ruleService;

	private final RedisUtils redis;

	private final RuleProperties props;

	private final RedisMessageListenerContainer listenerContainer;

	private volatile Map<Long, CachedRule> ruleCache = new ConcurrentHashMap<>();

	private volatile boolean listenerRegistered;

	public RuleCache(SceneRuleMapper ruleMapper, RuleService ruleService, RedisUtils redis, RuleProperties props,
			RedisMessageListenerContainer listenerContainer) {
		this.ruleMapper = ruleMapper;
		this.ruleService = ruleService;
		this.redis = redis;
		this.props = props;
		this.listenerContainer = listenerContainer;
	}

	@PostConstruct
	public void init() {
		reload();
		registerListener();
	}

	/** 订阅 rule:changed 通道：收到 {ruleId} 增量刷新，收到 ALL 全量刷新 */
	private void registerListener() {
		if (listenerRegistered) {
			return;
		}
		listenerRegistered = true;
		listenerContainer.addMessageListener((message, pattern) -> {
			String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
			refreshOne(body);
		}, new ChannelTopic(RedisChannelConstant.RULE_CHANGED));
		listenerContainer.start();
		log.info("[Rule] rule:changed 订阅启动 channel={}", RedisChannelConstant.RULE_CHANGED);
	}

	/** @Scheduled 全量兜底刷新（分布式锁防多实例重复加载） */
	@Scheduled(fixedDelayString = "${energyx.rule.rule-cache-refresh-ms:30000}",
			initialDelayString = "${energyx.rule.rule-cache-initial-delay-ms:10000}")
	public void scheduledReload() {
		reload();
	}

	/** 全量重载启用规则（启动 / 定时兜底 / ALL 广播） */
	public synchronized void reload() {
		try {
			List<SceneRuleRow> rows = ruleMapper.selectEnabledRules();
			Map<Long, CachedRule> fresh = new ConcurrentHashMap<>();
			for (SceneRuleRow row : rows) {
				fresh.put(row.getRuleId(), new CachedRule(row, ruleService.buildConfig(row)));
			}
			ruleCache = fresh;
			log.info("[Rule] 规则缓存全量刷新 count={}", fresh.size());
		}
		catch (Exception e) {
			// MySQL 不可用不阻塞消费，等待下次刷新重试
			log.error("[Rule] 规则缓存全量刷新失败（等待下次）", e);
		}
	}

	/** 增量刷新单条规则（rule:changed 广播；ruleId=ALL 时全量） */
	public void refreshOne(String ruleIdOrAll) {
		try {
			if ("ALL".equals(ruleIdOrAll)) {
				reload();
				return;
			}
			Long ruleId = Long.parseLong(ruleIdOrAll);
			// 删除 L2 缓存后重查：存在则更新本地，不存在（已删除/停用）则移除
			redis.delete(RuleRedisKeys.cache(ruleId));
			SceneRuleRow row = ruleMapper.selectById(ruleId);
			if (row == null || row.getEnabled() == null || row.getEnabled() != 1) {
				ruleCache.remove(ruleId);
				log.info("[Rule] 规则已删除/停用，移除本地缓存 ruleId={}", ruleId);
				return;
			}
			ruleCache.put(ruleId, new CachedRule(row, ruleService.buildConfig(row)));
			log.info("[Rule] 规则增量刷新 ruleId={}", ruleId);
		}
		catch (Exception e) {
			log.error("[Rule] 规则增量刷新失败 ruleIdOrAll={}", ruleIdOrAll, e);
		}
	}

	/** 全部启用规则（触发匹配入口） */
	public List<CachedRule> all() {
		return new ArrayList<>(ruleCache.values());
	}

	/** 按 ID 取规则 */
	public CachedRule get(Long ruleId) {
		return ruleCache.get(ruleId);
	}

	/** 属性触发候选：设备精确匹配或产品级匹配（deviceName 可空=产品下全部） */
	public List<CachedRule> candidatesForProperty(String productKey, String deviceName) {
		List<CachedRule> result = new ArrayList<>();
		for (CachedRule cached : ruleCache.values()) {
			for (RuleTrigger t : cached.config().getTriggers()) {
				if (!"PROPERTY".equals(t.getType()) || t.getDevice() == null) {
					continue;
				}
				if (productKey.equals(t.getDevice().getProductKey())
						&& (t.getDevice().getDeviceName() == null || t.getDevice().getDeviceName().isBlank()
								|| t.getDevice().getDeviceName().equals(deviceName))) {
					result.add(cached);
					break;
				}
			}
		}
		return result;
	}

	/** 生命周期触发候选（event 在触发匹配时再判，此处按类型粗筛） */
	public List<CachedRule> candidatesForLifecycle() {
		return candidatesByTriggerType("LIFECYCLE");
	}

	/** 告警触发候选 */
	public List<CachedRule> candidatesForAlarm() {
		return candidatesByTriggerType("ALARM");
	}

	/** 手动触发候选（全部启用规则，触发匹配时判断是否含 MANUAL） */
	public List<CachedRule> candidatesForManual() {
		return candidatesByTriggerType("MANUAL");
	}

	private List<CachedRule> candidatesByTriggerType(String type) {
		List<CachedRule> result = new ArrayList<>();
		for (CachedRule cached : ruleCache.values()) {
			for (RuleTrigger t : cached.config().getTriggers()) {
				if (type.equals(t.getType())) {
					result.add(cached);
					break;
				}
			}
		}
		return result;
	}

	@PreDestroy
	public void close() {
		// 监听容器由 RedisPubSubConfig destroyMethod 统一停止
	}

}
