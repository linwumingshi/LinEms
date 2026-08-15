package com.energyx.command.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.command.client.DeviceFeignClient;
import com.energyx.command.config.CommandProperties;
import com.energyx.command.mapper.CommandAckMapper;
import com.energyx.command.mapper.CommandMapper;
import com.energyx.command.model.CommandRow;
import com.energyx.command.model.DeviceInfo;
import com.energyx.command.mqtt.CommandKafkaProducer;
import com.energyx.command.util.CommandRedisKeys;
import com.energyx.command.web.dto.CommandView;
import com.energyx.command.web.dto.CreateCommandRequest;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.enums.CommandState;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.common.message.CommandDownMessage;
import com.energyx.common.message.ShadowDeltaMessage;
import com.energyx.common.model.Result;
import com.energyx.common.redis.IdempotencyUtils;
import com.energyx.common.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指令中心核心服务。
 *
 * <p>
 * <b>幂等性设计</b>：
 * <ul>
 * <li><b>创建幂等</b>：commandId 即幂等键（IdempotencyUtils，SETNX 24h），同 commandId 重试返回既有指令；</li>
 * <li><b>ACK 幂等</b>：状态迁移用「WHERE state ∈ 合法前驱」条件更新——Kafka 重放/重复 ACK 自然空操作，不设消息去重；</li>
 * <li><b>下发幂等</b>：QoS1 + commandId 业务锚点，补发仅当 state=0（CREATED）才置 SENT，避免重复投递。</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>在线/离线分流</b>：在线直发 Kafka（iot-command-down）+ 置 SENT；离线入队 iot:cmd:q（保持 CREATED），
 * 设备上线（lifecycle ONLINE）触发补发。ACK 超时由 @Scheduled 扫描驱动重试/终态。
 * </p>
 */
@Slf4j
@Service
public class CommandService {

	/** 影子 delta 物化的服务标识 */
	private static final String DELTA_COMMAND_NAME = "setProperties";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final CommandMapper commandMapper;

	private final CommandAckMapper ackMapper;

	private final DeviceFeignClient deviceFeignClient;

	private final StringRedisTemplate redis;

	private final CommandKafkaProducer producer;

	private final ObjectMapper objectMapper;

	private final IdempotencyUtils idempotencyUtils;

	private final CommandProperties props;

	private final SnowflakeIdGenerator idGenerator;

	public CommandService(CommandMapper commandMapper, CommandAckMapper ackMapper, DeviceFeignClient deviceFeignClient,
			StringRedisTemplate redis, CommandKafkaProducer producer, ObjectMapper objectMapper,
			IdempotencyUtils idempotencyUtils, CommandProperties props, SnowflakeIdGenerator idGenerator) {
		this.commandMapper = commandMapper;
		this.ackMapper = ackMapper;
		this.deviceFeignClient = deviceFeignClient;
		this.redis = redis;
		this.producer = producer;
		this.objectMapper = objectMapper;
		this.idempotencyUtils = idempotencyUtils;
		this.props = props;
		this.idGenerator = idGenerator;
	}

	/**
	 * 创建指令：解析设备 → 落库（CREATED）→ 在线直发 / 离线入队。 commandId 为空时服务端生成；传入时作为幂等键（重复创建返回既有指令）。
	 */
	public CommandView createCommand(CreateCommandRequest req) {
		String commandId = req.getCommandId() == null || req.getCommandId().isBlank() ? idGenerator.nextIdStr()
				: req.getCommandId();
		if (!idempotencyUtils.tryAcquire(commandId, props.getIdempotencyTtlSeconds())) {
			CommandRow existing = commandMapper.selectById(commandId);
			if (existing != null) {
				log.info("[Command] 幂等命中，返回既有指令 commandId={}", commandId);
				return toView(existing);
			}
			throw new IllegalArgumentException("重复的 commandId: " + commandId);
		}
		try {
			DeviceInfo dev = resolveDeviceByProductAndName(req.getProductKey(), req.getDeviceName());
			if (dev == null) {
				throw new IllegalArgumentException("设备不存在: " + req.getProductKey() + "/" + req.getDeviceName());
			}
			int maxRetry = req.getMaxRetry() == null ? props.getDefaultMaxRetry() : req.getMaxRetry();
			int timeoutMs = req.getTimeoutMs() == null ? props.getDefaultTimeoutMs() : req.getTimeoutMs();
			int commandType = req.getCommandType() == null ? 2 : req.getCommandType();
			// 实体落库：主键 INPUT 显式指定，租户/审计/逻辑删除由租户插件与 MetaObjectHandler 自动填充
			CommandRow row = new CommandRow();
			row.setCommandId(commandId);
			row.setDeviceId(dev.deviceId());
			row.setProductKey(dev.productKey());
			row.setCommandName(req.getCommand());
			row.setCommandType(commandType);
			row.setParams(toJson(req.getParams()));
			row.setState(CommandState.CREATED);
			row.setMaxRetry(maxRetry);
			row.setTimeoutMs(timeoutMs);
			row.setCreateBy(req.getCreateBy() == null ? 0L : req.getCreateBy());
			commandMapper.insert(row);
			dispatch(commandId, dev);
			return toView(commandMapper.selectById(commandId));
		}
		catch (Exception e) {
			idempotencyUtils.release(commandId); // 创建失败释放幂等许可，允许客户端重试
			throw e;
		}
	}

	/** 指令视图（含 deviceName） */
	public CommandView getCommand(String commandId) {
		CommandRow row = commandMapper.selectById(commandId);
		if (row == null) {
			return null;
		}
		return toView(row);
	}

	// ------------------------------------------------------------------
	// 消费端
	// ------------------------------------------------------------------

	/**
	 * ACK 状态机收敛（消费 iot-command-ack）。 条件更新天然幂等：终态忽略 / 非法转移忽略 / 重放空操作。
	 */
	public void applyAck(CommandAckMessage ack) {
		if (ack.getCommandId() == null || ack.getCommandId().isBlank()) {
			log.warn("[Command] ACK 缺 commandId，丢弃");
			return;
		}
		CommandState target = CommandState.fromAckStatus(ack.getStatus());
		if (target == null) {
			log.warn("[Command] 未知 ACK 状态 status={} commandId={}", ack.getStatus(), ack.getCommandId());
			return;
		}
		CommandRow row = commandMapper.selectById(ack.getCommandId());
		if (row == null) {
			log.warn("[Command] ACK 对应指令不存在 commandId={}", ack.getCommandId());
			return;
		}
		CommandState current = CommandState.fromCode(row.getState() == null ? 0 : row.getState().getCode());
		if (!CommandState.isAllowedAck(current, target)) {
			log.warn("[Command] 非法/重复 ACK 转移 commandId={} {} -> {}", ack.getCommandId(), current, target);
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		int updated = switch (target) {
			case DEVICE_RECEIVED -> commandMapper.updateReceived(ack.getCommandId(), now);
			case EXECUTING -> commandMapper.updateExecuting(ack.getCommandId(), now);
			case SUCCESS -> commandMapper.updateSuccess(ack.getCommandId(), toJson(ack.getResult()), now);
			case FAILED -> commandMapper.updateFailed(ack.getCommandId(), ack.getErrorCode(), now);
			case TIMEOUT -> commandMapper.markTerminalTimeout(ack.getCommandId(), now);
			default -> 0;
		};
		if (updated == 0) {
			log.info("[Command] ACK 转移未生效（并发/已变更） commandId={} {}", ack.getCommandId(), target);
			return;
		}
		if (target.isTerminal()) {
			clearInflight(row.getDeviceId(), ack.getCommandId());
		}
		persistAck(ack);
		log.info("[Command] ACK 生效 commandId={} {} -> {}", ack.getCommandId(), current, target);
	}

	/**
	 * 影子 delta → setProperties 指令（消费 iot-shadow-delta）。 同设备存在在途 setProperties
	 * 则合并跳过（shadow 收敛后再发新 delta）。
	 */
	public void materializeDelta(ShadowDeltaMessage delta) {
		if (delta.getDeviceId() == null || delta.getDesired() == null || delta.getDesired().isEmpty()) {
			return;
		}
		DeviceInfo dev = resolveDeviceById(delta.getDeviceId());
		if (dev == null) {
			log.warn("[Command] delta 设备不存在 deviceId={}", delta.getDeviceId());
			return;
		}
		String inflight = commandMapper.selectInFlightByDeviceAndName(dev.deviceId(), DELTA_COMMAND_NAME);
		if (inflight != null) {
			log.info("[Command] 存在在途 setProperties 指令 commandId={}，合并 delta deviceId={}", inflight, dev.deviceId());
			return;
		}
		String commandId = idGenerator.nextIdStr();
		// 实体落库（delta 物化指令，系统动作 createBy=0）
		CommandRow row = new CommandRow();
		row.setCommandId(commandId);
		row.setDeviceId(dev.deviceId());
		row.setProductKey(dev.productKey());
		row.setCommandName(DELTA_COMMAND_NAME);
		row.setCommandType(2);
		row.setParams(toJson(delta.getDesired()));
		row.setState(CommandState.CREATED);
		row.setMaxRetry(props.getDefaultMaxRetry());
		row.setTimeoutMs(props.getDefaultTimeoutMs());
		row.setCreateBy(0L);
		commandMapper.insert(row);
		dispatch(commandId, dev);
		log.info("[Command] delta 物化为 setProperties 指令 commandId={} deviceId={} keys={}", commandId, dev.deviceId(),
				delta.getDesired().size());
	}

	/** 设备上线补发离线队列（消费 iot-device-lifecycle ONLINE） */
	public void drainOfflineQueue(long deviceId) {
		String key = CommandRedisKeys.offlineQueue(deviceId);
		int drained = 0;
		String json;
		while (drained < props.getOfflineQueueDrainMax() && (json = redis.opsForList().leftPop(key)) != null) {
			drained++;
			try {
				CommandDownMessage m = objectMapper.readValue(json, CommandDownMessage.class);
				// 补发仅当仍为 CREATED（未被其他路径下发/重发），防重复投递
				int updated = commandMapper.updateSent(m.getCommandId(), LocalDateTime.now());
				if (updated > 0) {
					producer.send(KafkaTopicConstant.IOT_COMMAND_DOWN, String.valueOf(deviceId), json);
					markInflight(deviceId, m.getCommandId());
					log.info("[Command] 离线队列补发 commandId={} deviceId={}", m.getCommandId(), deviceId);
				}
				else {
					log.info("[Command] 离线队列指令已非 CREATED，跳过 commandId={}", m.getCommandId());
				}
			}
			catch (Exception e) {
				log.error("[Command] 离线队列补发失败 deviceId={} item={}", deviceId, json, e);
			}
		}
		if (drained > 0) {
			log.info("[Command] 离线队列补发完成 deviceId={} drained={}", deviceId, drained);
		}
	}

	// ------------------------------------------------------------------
	// 定时扫描
	// ------------------------------------------------------------------

	/**
	 * ACK 超时扫描：在途指令 sent_time 超时 → 设备在线重发 / 离线重新入队；重试耗尽置 TIMEOUT 终态。
	 */
	public void timeoutScan() {
		LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(props.getDefaultTimeoutMs()));
		var candidates = commandMapper.selectTimeoutCandidates(cutoff, props.getScanBatchSize());
		if (candidates.isEmpty()) {
			return;
		}
		log.info("[Command] 超时扫描命中 {} 条", candidates.size());
		for (CommandRow row : candidates) {
			try {
				if (row.getRetryCount() < row.getMaxRetry()) {
					retryOnce(row);
				}
				else {
					int updated = commandMapper.markTerminalTimeout(row.getCommandId(), LocalDateTime.now());
					if (updated > 0) {
						clearInflight(row.getDeviceId(), row.getCommandId());
						log.warn("[Command] 超时终态 commandId={} 重试耗尽", row.getCommandId());
					}
				}
			}
			catch (Exception e) {
				log.error("[Command] 超时扫描处理失败 commandId={}", row.getCommandId(), e);
			}
		}
	}

	private void retryOnce(CommandRow row) {
		LocalDateTime now = LocalDateTime.now();
		if (isOnline(row.getDeviceId())) {
			int updated = commandMapper.resendOnline(row.getCommandId(), now);
			if (updated == 0) {
				return;
			}
			DeviceInfo dev = resolveDeviceById(row.getDeviceId());
			if (dev != null) {
				producer.send(KafkaTopicConstant.IOT_COMMAND_DOWN, String.valueOf(row.getDeviceId()),
						toJson(buildDown(row, dev)));
				markInflight(row.getDeviceId(), row.getCommandId());
			}
			log.info("[Command] 超时重发 commandId={} retry={}/{}", row.getCommandId(), row.getRetryCount() + 1,
					row.getMaxRetry());
		}
		else {
			int updated = commandMapper.requeue(row.getCommandId(), now);
			if (updated == 0) {
				return;
			}
			DeviceInfo dev = resolveDeviceById(row.getDeviceId());
			if (dev != null) {
				enqueueOffline(row, dev);
			}
			log.info("[Command] 超时重新入队 commandId={} retry={}/{}", row.getCommandId(), row.getRetryCount() + 1,
					row.getMaxRetry());
		}
	}

	// ------------------------------------------------------------------
	// 下发 / 入队 / 在途
	// ------------------------------------------------------------------

	/** 在线直发 Kafka；离线写入离线队列（保持 CREATED，待上线补发） */
	private void dispatch(String commandId, DeviceInfo dev) {
		CommandRow row = commandMapper.selectById(commandId);
		if (row == null) {
			log.warn("[Command] 指令未落库，无法下发 commandId={}", commandId);
			return;
		}
		if (isOnline(dev.deviceId())) {
			CommandDownMessage m = buildDown(row, dev);
			producer.send(KafkaTopicConstant.IOT_COMMAND_DOWN, String.valueOf(dev.deviceId()), toJson(m));
			commandMapper.updateSent(commandId, LocalDateTime.now());
			markInflight(dev.deviceId(), commandId);
			log.info("[Command] 在线直发 commandId={} deviceId={} command={}", commandId, dev.deviceId(),
					row.getCommandName());
		}
		else {
			enqueueOffline(row, dev);
		}
	}

	/** 构建下行消息（需完整设备信息拼 topic 所需的 pk/dn） */
	private CommandDownMessage buildDown(CommandRow row, DeviceInfo dev) {
		CommandDownMessage m = new CommandDownMessage();
		m.setCommandId(row.getCommandId());
		m.setDeviceId(dev.deviceId());
		m.setTenantId(dev.tenantId());
		m.setProductKey(dev.productKey());
		m.setDeviceName(dev.deviceName());
		m.setCommand(row.getCommandName());
		m.setParams(parse(row.getParams()));
		m.setQos(1);
		m.setTs(System.currentTimeMillis());
		return m;
	}

	private void enqueueOffline(CommandRow row, DeviceInfo dev) {
		String key = CommandRedisKeys.offlineQueue(dev.deviceId());
		String json = toJson(buildDown(row, dev));
		Long len = redis.opsForList().rightPush(key, json);
		if (len != null && len > props.getOfflineQueueMax()) {
			redis.opsForList().leftPop(key); // 超限丢最旧
			log.warn("[Command] 离线队列超限 deviceId={} 丢弃最旧 commandId={}", dev.deviceId(), row.getCommandId());
		}
		redis.expire(key, Duration.ofDays(7));
		log.info("[Command] 离线入队 commandId={} deviceId={}", row.getCommandId(), dev.deviceId());
	}

	private void markInflight(long deviceId, String commandId) {
		try {
			redis.opsForHash()
				.put(CommandRedisKeys.inflight(deviceId), commandId,
						String.valueOf(System.currentTimeMillis() + props.getInflightTtlMs()));
			redis.expire(CommandRedisKeys.inflight(deviceId), Duration.ofMillis(props.getInflightTtlMs()));
		}
		catch (Exception e) {
			log.debug("[Command] 在途标记失败 deviceId={} commandId={}", deviceId, commandId, e);
		}
	}

	private void clearInflight(long deviceId, String commandId) {
		try {
			redis.opsForHash().delete(CommandRedisKeys.inflight(deviceId), commandId);
		}
		catch (Exception e) {
			log.debug("[Command] 在途清除失败 deviceId={} commandId={}", deviceId, commandId, e);
		}
	}

	private boolean isOnline(long deviceId) {
		return Boolean.TRUE.equals(redis.hasKey(CommandRedisKeys.online(deviceId)));
	}

	private void persistAck(CommandAckMessage ack) {
		try {
			ackMapper.insertAck(idGenerator.nextId(), ack.getCommandId(),
					ack.getDeviceId() == null ? 0L : ack.getDeviceId(), toJson(ack));
		}
		catch (Exception e) {
			log.warn("[Command] ACK 留存失败 commandId={}", ack.getCommandId(), e);
		}
	}

	private CommandView toView(CommandRow row) {
		CommandView view = new CommandView();
		view.setCommandId(row.getCommandId());
		view.setTenantId(row.getTenantId());
		view.setDeviceId(row.getDeviceId());
		view.setProductKey(row.getProductKey());
		view.setCommand(row.getCommandName());
		view.setCommandType(row.getCommandType());
		view.setParams(parse(row.getParams()));
		int state = row.getState() == null ? 0 : row.getState().getCode();
		view.setState(state);
		view.setStateName(CommandState.fromCode(state).name());
		view.setRetryCount(row.getRetryCount());
		view.setMaxRetry(row.getMaxRetry());
		view.setTimeoutMs(row.getTimeoutMs());
		view.setSentTime(row.getSentTime());
		view.setReceivedTime(row.getReceivedTime());
		view.setExecutingTime(row.getExecutingTime());
		view.setFinishTime(row.getFinishTime());
		view.setResult(parse(row.getResult()));
		view.setErrorCode(row.getErrorCode());
		view.setErrorMsg(row.getErrorMsg());
		view.setCreateBy(row.getCreateBy());
		view.setCreateTime(row.getCreateTime());
		return view;
	}

	private Map<String, Object> parse(String json) {
		if (json == null || json.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (Exception e) {
			log.warn("[Command] JSON 解析失败 json={}", json, e);
			return new LinkedHashMap<>();
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
			throw new IllegalStateException("指令 JSON 序列化失败: " + value, e);
		}
	}

	/**
	 * 按设备 ID 解析设备身份（Feign 调 device 服务；服务不可用降级返回 null 视为设备不存在）。
	 */
	private DeviceInfo resolveDeviceById(long deviceId) {
		Result<DeviceInfo> result = deviceFeignClient.byId(deviceId);
		if (result == null || !result.isSuccess()) {
			log.warn("[Command] 设备解析失败 deviceId={} result={}", deviceId, result);
			return null;
		}
		return result.getData();
	}

	/**
	 * 按 productKey + deviceName 解析设备身份（Feign 调 device 服务）。
	 */
	private DeviceInfo resolveDeviceByProductAndName(String productKey, String deviceName) {
		Result<DeviceInfo> result = deviceFeignClient.byName(productKey, deviceName);
		if (result == null || !result.isSuccess()) {
			log.warn("[Command] 设备解析失败 productKey={} deviceName={} result={}", productKey, deviceName, result);
			return null;
		}
		return result.getData();
	}

}
