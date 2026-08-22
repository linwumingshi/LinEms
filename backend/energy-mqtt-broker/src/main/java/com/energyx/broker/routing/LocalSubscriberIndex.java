package com.energyx.broker.routing;

import com.energyx.broker.session.Session;
import com.energyx.broker.util.TopicMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 节点内订阅索引（topic trie，纯内存）。
 *
 * <p>
 * 结构：按 "/" 分层建树；{@code +} 作为特殊子节点；{@code #} 作为锚定在所在层级节点的 终端绑定集合（hashBindings）。匹配复杂度
 * O(topic 层级数)（典型 ≤5），替代原 O(N) 线性扫描， 支撑 50 万级订阅规模下的高吞吐匹配。
 * </p>
 *
 * <ul>
 * <li>绑定按 deviceKey 去重：重连直接覆盖（新 Session 替换幽灵 Session）；同设备多 filter 命中时 match 结果取最高
 * QoS；</li>
 * <li>持久会话离线后幽灵绑定保留在索引，发布匹配时由投递层转入离线队列；</li>
 * <li>反向索引 deviceKey → filters 使 removeAll 为 O(单设备订阅数) 而非 O(N)；</li>
 * <li>共享订阅（$share/{group}/{filter}，阶段 2 P1-11）：同一原始 filter 的订阅者组成一个
 * 订阅组，发布时组内仅选一个订阅者投递（轮询负载均衡，QoS 取组内最大），修正 「剥前缀后全组广播」的语义错误；不同 group 互为独立负载均衡组；</li>
 * <li>并发：match 走读锁（高并发），add/remove 走写锁（低频）；节点只增不删 （空节点内存有界，以 distinct
 * 层级前缀为上界），避免删除竞态。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalSubscriberIndex {

	private static final String PLUS = "+";

	private static final String HASH = "#";

	/** 共享订阅前缀 $share/{group}/ */
	private static final String SHARE_PREFIX = "$share/";

	private final TrieNode root = new TrieNode();

	/** deviceKey → 其订阅的原始 filter 集合（removeAll 反向索引） */
	private final Map<String, Set<String>> filtersByDevice = new ConcurrentHashMap<>();

	/** 共享订阅组轮询游标：shareFilter → 自增计数（匹配时对组内成员取模选一） */
	private final Map<String, AtomicInteger> shareCounters = new ConcurrentHashMap<>();

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * 订阅/更新（同 deviceKey 重复订阅以最高 QoS 为准，Session 以最新为准）。
	 * @param session 订阅的会话（携带 deviceKey）
	 * @param topicFilter 订阅表达式（支持 +/# 通配与 $share 共享订阅前缀）
	 * @param qos 请求的服务质量等级（与历史订阅取最大值）
	 */
	public void add(Session session, String topicFilter, int qos) {
		ShareInfo share = ShareInfo.parse(topicFilter);
		String[] levels = share.filter().split("/", -1);
		// 写锁保护结构变更（低频）；节点只增不删，避免删除竞态
		lock.writeLock().lock();
		try {
			TrieNode node = root;
			boolean anchored = false;
			// 逐层级下行建树：非末层走精确子节点，遇末层 "#" 则锚定到当前层级节点
			for (int i = 0; i < levels.length; i++) {
				String level = levels[i];
				if (HASH.equals(level) && i == levels.length - 1) {
					// 末层 "#"：多层级通配绑定（匹配零个或多个后续层级），按 deviceKey 去重、QoS 取最高
					node.hashBindings.merge(session.getDeviceKey(),
							new SubscriberBinding(session, qos, share.shareFilter()),
							(a, b) -> new SubscriberBinding(b.session(), Math.max(a.qos(), b.qos()), b.shareFilter()));
					anchored = true;
					break;
				}
				node = node.children.computeIfAbsent(level, k -> new TrieNode());
			}
			if (!anchored) {
				// 精确终止绑定（单层级精确匹配），同 deviceKey 以最高 QoS 覆盖（重连替换幽灵 Session）
				node.exactBindings.merge(session.getDeviceKey(),
						new SubscriberBinding(session, qos, share.shareFilter()),
						(a, b) -> new SubscriberBinding(b.session(), Math.max(a.qos(), b.qos()), b.shareFilter()));
			}
			// 反向索引：deviceKey → 原始 filter，供 removeAll 精确反查（O(单设备订阅数) 而非 O(N)）
			filtersByDevice.computeIfAbsent(session.getDeviceKey(), k -> ConcurrentHashMap.newKeySet())
				.add(topicFilter);
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * 取消单条订阅（按 deviceKey + topicFilter 精确移除 trie 绑定与反向索引）。
	 * @param deviceKey 设备唯一键
	 * @param topicFilter 订阅表达式（共享订阅自动剥前缀）
	 */
	public void remove(String deviceKey, String topicFilter) {
		// 先剥 $share 前缀再按层级定位（共享订阅的 trie 仅存真实 filter）
		String[] levels = TopicMatcher.stripSharePrefix(topicFilter).split("/", -1);
		lock.writeLock().lock();
		try {
			TrieNode node = navigate(levels);
			if (node != null) {
				// 末层为 "#" 走 hashBindings，否则走 exactBindings，按 deviceKey 精确移除
				if (HASH.equals(levels[levels.length - 1])) {
					node.hashBindings.remove(deviceKey);
				}
				else {
					node.exactBindings.remove(deviceKey);
				}
			}
			// 同步清理反向索引；deviceKey 再无订阅时整体移除该键，回收内存
			Set<String> filters = filtersByDevice.get(deviceKey);
			if (filters != null) {
				filters.remove(topicFilter);
				if (filters.isEmpty()) {
					filtersByDevice.remove(deviceKey, filters);
				}
			}
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * 会话断开：移除该设备全部绑定（O(单设备订阅数)，持久会话幽灵由调用方决定是否调用）。
	 * @param deviceKey 设备唯一键
	 */
	public void removeAll(String deviceKey) {
		lock.writeLock().lock();
		try {
			// 借反向索引 O(单设备订阅数) 反查全部 filter，避免整树扫描
			Set<String> filters = filtersByDevice.remove(deviceKey);
			if (filters == null) {
				return;
			}
			for (String filter : filters) {
				String[] levels = TopicMatcher.stripSharePrefix(filter).split("/", -1);
				TrieNode node = navigate(levels);
				if (node != null) {
					// 末层 "#" 走 hashBindings，否则走 exactBindings
					if (HASH.equals(levels[levels.length - 1])) {
						node.hashBindings.remove(deviceKey);
					}
					else {
						node.exactBindings.remove(deviceKey);
					}
				}
			}
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * 匹配 topic，返回去重后的绑定（同一 deviceKey 多条 filter 命中取最高 QoS）。 共享订阅组内仅保留一个订阅者（轮询负载均衡，QoS
	 * 取组内最大）。O(topic 层级数)。
	 * @param topic 待匹配的发布主题
	 * @return 去重后的订阅者匹配列表（共享订阅已归并为组内单一成员）
	 */
	public List<SubscriberMatch> match(String topic) {
		if (topic == null || topic.isEmpty()) {
			return List.of();
		}
		String[] levels = topic.split("/", -1);
		Map<String, SubscriberMatch> hits = new HashMap<>();
		// 读锁支撑高并发匹配；以 $ 开头的系统 topic 在根层禁用通配符（保留语义）
		lock.readLock().lock();
		try {
			collect(root, levels, 0, !topic.startsWith("$"), hits);
		}
		finally {
			lock.readLock().unlock();
		}
		// 共享订阅归并：同组仅保留一个订阅者（轮询），普通订阅全部保留
		return dedupeShared(hits);
	}

	/**
	 * 共享订阅归并：同 shareFilter 的多个订阅者只保留一个（轮询），QoS 取组内最大； 普通订阅（shareFilter=null）全部保留。
	 * @param hits 递归收集到的原始命中集合（deviceKey → 匹配）
	 * @return 归并后的订阅者匹配列表
	 */
	private List<SubscriberMatch> dedupeShared(Map<String, SubscriberMatch> hits) {
		// 普通订阅与共享订阅分组
		List<SubscriberMatch> normal = new ArrayList<>();
		Map<String, List<SubscriberMatch>> sharedGroups = new HashMap<>();
		Map<String, Integer> maxQosByShare = new HashMap<>();
		for (SubscriberMatch m : hits.values()) {
			if (m.shareFilter() == null) {
				normal.add(m);
			}
			else {
				sharedGroups.computeIfAbsent(m.shareFilter(), k -> new ArrayList<>()).add(m);
				maxQosByShare.merge(m.shareFilter(), m.qos(), Math::max);
			}
		}
		List<SubscriberMatch> result = new ArrayList<>(normal.size() + sharedGroups.size());
		result.addAll(normal);
		for (Map.Entry<String, List<SubscriberMatch>> e : sharedGroups.entrySet()) {
			List<SubscriberMatch> members = e.getValue();
			// 轮询选择组内一个订阅者；QoS 取组内最大授予值（MQTT 5 §4.8.2）
			AtomicInteger cursor = shareCounters.computeIfAbsent(e.getKey(), k -> new AtomicInteger());
			SubscriberMatch selected = members.get(Math.floorMod(cursor.getAndIncrement(), members.size()));
			result.add(new SubscriberMatch(selected.session(), maxQosByShare.get(e.getKey()), e.getKey()));
		}
		return result;
	}

	/**
	 * 递归收集：当前节点的 # 锚定绑定恒命中（匹配零个或多个层级）； 层级未耗尽时向「精确子节点」与「+ 子节点」下行；耗尽时收集精确绑定。
	 * @param node 当前遍历的 trie 节点
	 * @param levels 主题按 "/" 切分后的层级数组
	 * @param depth 当前已匹配的层级深度（从 0 开始）
	 * @param allowWildcardHere 当前位置是否允许通配符（topic 以 $ 开头时根层禁用）
	 * @param hits 命中结果累积表（deviceKey → 匹配，内部取最高 QoS）
	 */
	private void collect(TrieNode node, String[] levels, int depth, boolean allowWildcardHere,
			Map<String, SubscriberMatch> hits) {
		if (allowWildcardHere) {
			node.hashBindings.forEach((deviceKey, b) -> mergeHit(hits, deviceKey, b));
		}
		if (depth == levels.length) {
			node.exactBindings.forEach((deviceKey, b) -> mergeHit(hits, deviceKey, b));
			return;
		}
		TrieNode exact = node.children.get(levels[depth]);
		if (exact != null) {
			collect(exact, levels, depth + 1, true, hits);
		}
		if (allowWildcardHere) {
			TrieNode plus = node.children.get(PLUS);
			if (plus != null) {
				collect(plus, levels, depth + 1, true, hits);
			}
		}
	}

	/**
	 * 将单个绑定并入命中表：同 deviceKey 已存在时仅保留更高 QoS（多 filter 命中取最高）。
	 * @param hits 命中结果累积表
	 * @param deviceKey 设备唯一键
	 * @param b 待并入的订阅绑定
	 */
	private void mergeHit(Map<String, SubscriberMatch> hits, String deviceKey, SubscriberBinding b) {
		SubscriberMatch existing = hits.get(deviceKey);
		if (existing == null || b.qos() > existing.qos()) {
			hits.put(deviceKey, new SubscriberMatch(b.session(), b.qos(), b.shareFilter()));
		}
	}

	/**
	 * 按层级下行到目标节点（"#" 锚定在其前一层级节点上）。
	 * @param levels 订阅表达式按 "/" 切分后的层级数组
	 * @return 目标 trie 节点；中途节点缺失返回 null
	 */
	private TrieNode navigate(String[] levels) {
		TrieNode node = root;
		// 沿精确层级下行；末层为 "#" 表示锚定在前一层级节点上，直接返回该节点
		for (int i = 0; i < levels.length; i++) {
			if (HASH.equals(levels[i]) && i == levels.length - 1) {
				return node;
			}
			node = node.children.get(levels[i]);
			if (node == null) {
				return null;
			}
		}
		return node;
	}

	public int size() {
		int count = 0;
		for (Set<String> s : filtersByDevice.values()) {
			count += s.size();
		}
		return count;
	}

	/** trie 节点：children 按层级名索引（含 "+" 特殊键）；# 锚定绑定与精确终止绑定分离存储 */
	private static final class TrieNode {

		private final Map<String, TrieNode> children = new ConcurrentHashMap<>();

		/** 终止于本层级的精确 filter 绑定（deviceKey → binding） */
		private final Map<String, SubscriberBinding> exactBindings = new ConcurrentHashMap<>();

		/** 形如 "本层级/#" 的锚定绑定（deviceKey → binding） */
		private final Map<String, SubscriberBinding> hashBindings = new ConcurrentHashMap<>();

	}

	/** 共享订阅信息：group=null 表示普通订阅；filter 为剥离前缀后的真实表达式；shareFilter 为完整原始 filter */
	private record ShareInfo(String group, String filter, String shareFilter) {

		/**
		 * 解析 filter：$share/{group}/{真实filter} → 三元组；普通订阅 group/shareFilter 为 null。
		 * @param topicFilter 原始订阅表达式
		 * @return 共享订阅信息（普通订阅 group/shareFilter 为 null）
		 */
		static ShareInfo parse(String topicFilter) {
			if (topicFilter != null && topicFilter.startsWith(SHARE_PREFIX)) {
				int groupStart = SHARE_PREFIX.length();
				int groupEnd = topicFilter.indexOf('/', groupStart);
				if (groupEnd > groupStart) {
					String group = topicFilter.substring(groupStart, groupEnd);
					String realFilter = topicFilter.substring(groupEnd + 1);
					if (!realFilter.isEmpty()) {
						return new ShareInfo(group, realFilter, topicFilter);
					}
				}
			}
			return new ShareInfo(null, topicFilter, null);
		}
	}

	public record SubscriberBinding(Session session, int qos, String shareFilter) {
	}

	/** shareFilter=null 表示普通订阅；非 null 表示该匹配来自共享订阅组（按组去重后仅一个） */
	public record SubscriberMatch(Session session, int qos, String shareFilter) {
	}

}
