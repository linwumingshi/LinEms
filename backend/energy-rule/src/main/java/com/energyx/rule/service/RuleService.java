package com.energyx.rule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.model.PageResult;
import com.energyx.common.redis.RedisChannelConstant;
import com.energyx.rule.engine.DslValidator;
import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.mapper.SceneRuleMapper;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleCondition;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleRecovery;
import com.energyx.rule.model.RuleTrigger;
import com.energyx.rule.util.RuleRedisKeys;
import com.energyx.rule.web.dto.RuleView;
import com.energyx.rule.web.dto.SaveRuleRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景联动规则管理服务（CRUD + DSL 校验 + 热更新广播）。
 *
 * <p>
 * 写路径：MySQL 落库（乐观锁 version）→ 发布 {@code rule:changed}（{ruleId}）→ 各实例 订阅后增量刷新本地缓存 + 删除
 * rule:cache:{ruleId}（Phase B 引擎消费该信号重建索引）。
 * </p>
 */
@Slf4j
@Service
public class RuleService {

	private static final TypeReference<RuleConfig> CONFIG_TYPE = new TypeReference<>() {
	};

	private final SceneRuleMapper ruleMapper;

	private final DslValidator dslValidator;

	private final ObjectMapper objectMapper;

	private final StringRedisTemplate redis;

	public RuleService(SceneRuleMapper ruleMapper, DslValidator dslValidator, ObjectMapper objectMapper,
			StringRedisTemplate redis) {
		this.ruleMapper = ruleMapper;
		this.dslValidator = dslValidator;
		this.objectMapper = objectMapper;
		this.redis = redis;
	}

	/** 创建规则：校验 DSL → 落库（enabled 由请求决定）→ 广播热更新 */
	@Transactional(rollbackFor = Exception.class)
	public RuleView create(SaveRuleRequest req) {
		RuleConfig config = req.getDsl();
		dslValidator.validate(config, null);
		SceneRuleRow row = new SceneRuleRow();
		row.setTenantId(1L); // 多租户上下文由 TenantContext 注入，缺省 1（单租户环境）
		row.setRuleCode(req.getRuleCode());
		row.setRuleName(req.getRuleName());
		row.setDescription(req.getDescription());
		row.setDslVersion(config.getDslVersion() == null ? 1 : config.getDslVersion());
		row.setTriggerJson(toJson(config.getTriggers()));
		row.setConditionJson(toJson(config.getConditions()));
		row.setActionJson(toJson(config.getActions()));
		row.setRecoveryJson(config.getRecovery() == null ? null : toJson(config.getRecovery()));
		row.setDebounceSeconds(req.getDebounceSeconds() == null ? 300 : req.getDebounceSeconds());
		row.setPriority(req.getPriority() == null ? 100 : req.getPriority());
		row.setEnabled(Boolean.TRUE.equals(req.getEnabled()) ? 1 : 0);
		row.setVersion(0);
		row.setCreateBy(req.getCreateBy() == null ? 0L : req.getCreateBy());
		ruleMapper.insert(row);
		final Long committedRuleId = row.getRuleId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
			@Override
			public void afterCommit() {
				notifyChanged(committedRuleId);
			}
		});
		log.info("[Rule] 创建规则 ruleId={} code={} enabled={}", row.getRuleId(), row.getRuleCode(), row.getEnabled());
		return toView(ruleMapper.selectById(row.getRuleId()));
	}

	/** 更新规则：乐观锁 version → 广播热更新 */
	@Transactional(rollbackFor = Exception.class)
	public RuleView update(Long ruleId, SaveRuleRequest req) {
		SceneRuleRow existing = ruleMapper.selectById(ruleId);
		if (existing == null) {
			throw new IllegalArgumentException("规则不存在: " + ruleId);
		}
		if (req.getVersion() == null || !req.getVersion().equals(existing.getVersion())) {
			throw new IllegalArgumentException("规则已被他人修改，请刷新后重试（版本冲突）");
		}
		RuleConfig config = req.getDsl();
		dslValidator.validate(config, ruleId);
		SceneRuleRow row = new SceneRuleRow();
		row.setRuleId(ruleId);
		row.setTenantId(existing.getTenantId());
		row.setRuleCode(req.getRuleCode());
		row.setRuleName(req.getRuleName());
		row.setDescription(req.getDescription());
		row.setDslVersion(config.getDslVersion() == null ? 1 : config.getDslVersion());
		row.setTriggerJson(toJson(config.getTriggers()));
		row.setConditionJson(toJson(config.getConditions()));
		row.setActionJson(toJson(config.getActions()));
		row.setRecoveryJson(config.getRecovery() == null ? null : toJson(config.getRecovery()));
		row.setDebounceSeconds(req.getDebounceSeconds() == null ? 300 : req.getDebounceSeconds());
		row.setPriority(req.getPriority() == null ? 100 : req.getPriority());
		row.setEnabled(existing.getEnabled());
		row.setVersion(existing.getVersion());
		int updated = ruleMapper.updateOptimistic(row);
		if (updated == 0) {
			throw new IllegalArgumentException("规则更新冲突，请刷新后重试");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
			@Override
			public void afterCommit() {
				notifyChanged(ruleId);
			}
		});
		log.info("[Rule] 更新规则 ruleId={} code={}", ruleId, row.getRuleCode());
		return toView(ruleMapper.selectById(ruleId));
	}

	/** 删除规则：先停用再删除（防在途执行）→ 广播热更新 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long ruleId) {
		SceneRuleRow existing = ruleMapper.selectById(ruleId);
		if (existing == null) {
			return;
		}
		ruleMapper.updateEnabled(ruleId, 0);
		ruleMapper.deleteById(ruleId);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
			@Override
			public void afterCommit() {
				notifyChanged(ruleId);
			}
		});
		log.info("[Rule] 删除规则 ruleId={} code={}", ruleId, existing.getRuleCode());
	}

	/** 启停切换：enabled 0/1 → 广播热更新 */
	public void setEnabled(Long ruleId, boolean enabled) {
		SceneRuleRow existing = ruleMapper.selectById(ruleId);
		if (existing == null) {
			throw new IllegalArgumentException("规则不存在: " + ruleId);
		}
		ruleMapper.updateEnabled(ruleId, enabled ? 1 : 0);
		notifyChanged(ruleId);
		log.info("[Rule] 规则启停 ruleId={} enabled={}", ruleId, enabled);
	}

	/** 规则详情 */
	public RuleView get(Long ruleId) {
		SceneRuleRow row = ruleMapper.selectById(ruleId);
		return row == null ? null : toView(row);
	}

	/** 分页查询 */
	public PageResult<RuleView> page(Long tenantId, String ruleName, Integer enabled, long page, long size) {
		long offset = (page - 1) * size;
		List<SceneRuleRow> rows = ruleMapper.selectPage(tenantId, ruleName, enabled, offset, size);
		long total = ruleMapper.countPage(tenantId, ruleName, enabled);
		List<RuleView> views = new ArrayList<>(rows.size());
		for (SceneRuleRow row : rows) {
			views.add(toView(row));
		}
		return PageResult.of(total, page, size, views);
	}

	// ------------------------------------------------------------------
	// 内部工具
	// ------------------------------------------------------------------

	private void notifyChanged(Long ruleId) {
		try {
			redis.convertAndSend(RedisChannelConstant.RULE_CHANGED, String.valueOf(ruleId));
			redis.delete(RuleRedisKeys.cache(ruleId));
		}
		catch (Exception e) {
			log.warn("[Rule] 热更新广播失败 ruleId={}", ruleId, e);
		}
	}

	private RuleView toView(SceneRuleRow row) {
		RuleView view = new RuleView();
		view.setRuleId(row.getRuleId());
		view.setTenantId(row.getTenantId());
		view.setRuleCode(row.getRuleCode());
		view.setRuleName(row.getRuleName());
		view.setDescription(row.getDescription());
		view.setDslVersion(row.getDslVersion());
		view.setDsl(buildConfig(row));
		view.setDebounceSeconds(row.getDebounceSeconds());
		view.setPriority(row.getPriority());
		view.setEnabled(row.getEnabled());
		view.setVersion(row.getVersion());
		view.setCreateBy(row.getCreateBy());
		view.setCreateTime(row.getCreateTime());
		view.setUpdateTime(row.getUpdateTime());
		return view;
	}

	/** 从行投影拼装 DSL 配置（供详情/引擎缓存使用） */
	public RuleConfig buildConfig(SceneRuleRow row) {
		RuleConfig config = new RuleConfig();
		config.setDslVersion(row.getDslVersion());
		config.setName(row.getRuleName());
		config.setTriggers(parseList(row.getTriggerJson(), new TypeReference<List<RuleTrigger>>() {
		}));
		config.setConditions(parseList(row.getConditionJson(), new TypeReference<List<RuleCondition>>() {
		}));
		config.setActions(parseList(row.getActionJson(), new TypeReference<List<RuleAction>>() {
		}));
		config.setRecovery(row.getRecoveryJson() == null ? null : parseOne(row.getRecoveryJson(), RuleRecovery.class));
		return config;
	}

	private <T> List<T> parseList(String json, TypeReference<List<T>> type) {
		if (json == null || json.isBlank()) {
			return new ArrayList<>();
		}
		try {
			return objectMapper.readValue(json, type);
		}
		catch (Exception e) {
			log.warn("[Rule] DSL JSON 解析失败 json={}", json, e);
			return new ArrayList<>();
		}
	}

	private <T> T parseOne(String json, Class<T> type) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(json, type);
		}
		catch (Exception e) {
			log.warn("[Rule] DSL JSON 解析失败 json={}", json, e);
			return null;
		}
	}

	private String toJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception e) {
			throw new IllegalStateException("规则 JSON 序列化失败: " + value, e);
		}
	}

}
