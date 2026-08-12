package com.energyx.ems.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.enums.PlanPointState;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.ems.entity.EmsExecutionRecord;
import com.energyx.ems.mapper.EmsExecutionRecordMapper;
import com.energyx.ems.service.EmsPlanService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 指令 ACK 回写消费者（P0 执行闭环：执行结果回写 + 计划状态推进）。
 *
 * <p>
 * 消费 iot-command-ack（key=commandId），按 commandId 定位 ems_execution_record 回写点状态与回执，
 * 随后触发计划状态推进（全部点终态 → 计划完成/失败）。非本域指令（commandId 不在 exec 表）直接忽略。
 * </p>
 *
 * <p>
 * 幂等：ACK 状态回写为覆盖式 UPDATE（终态覆盖中间态），重复 ACK/重放自然收敛，无需消息级去重 （对齐 energy-command 的 ACK 幂等矩阵）。
 * </p>
 */
@Slf4j
@Component
public class EmsAckConsumer implements KafkaRecordHandler {

	/** ACK 终态 → 执行记录 state 映射（DEVICE_RECEIVED/EXECUTING 中间态保持 1 已下发） */
	private static final String ACK_SUCCESS = "SUCCESS";

	private static final String ACK_FAILED = "FAILED";

	private static final String ACK_TIMEOUT = "TIMEOUT";

	private final ObjectMapper objectMapper;

	private final EmsExecutionRecordMapper execMapper;

	private final EmsPlanService planService;

	public EmsAckConsumer(ObjectMapper objectMapper, EmsExecutionRecordMapper execMapper, EmsPlanService planService) {
		this.objectMapper = objectMapper;
		this.execMapper = execMapper;
		this.planService = planService;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		CommandAckMessage ack = objectMapper.readValue(record.value(), CommandAckMessage.class);
		if (ack.getCommandId() == null || ack.getCommandId().isBlank()) {
			log.warn("[EmsAck] ACK 缺 commandId，丢弃");
			return;
		}
		EmsExecutionRecord exec = execMapper.selectByCommandId(ack.getCommandId());
		if (exec == null) {
			log.debug("[EmsAck] 非计划指令 ACK，忽略 commandId={}", ack.getCommandId());
			return;
		}
		PlanPointState state = mapState(ack.getStatus());
		String result = ack.getResult() == null ? null : objectMapper.writeValueAsString(ack.getResult());
		if (state != null) {
			execMapper.updateStateAndResult(exec.getExecId(), state.getCode(), result);
			log.info("[EmsAck] 回写执行记录 execId={} commandId={} state={}", exec.getExecId(), ack.getCommandId(),
					state.getCode());
		}
		// 任何 ACK（含中间态）都尝试推进计划状态：终态足够时收敛，中间态不影响
		planService.refreshPlanStatus(exec.getPlanId());
	}

	/** ACK 状态 → 执行记录 state；中间态返回 null（保持已下发） */
	private PlanPointState mapState(String ackStatus) {
		if (ackStatus == null) {
			return null;
		}
		return switch (ackStatus) {
			case ACK_SUCCESS -> PlanPointState.SUCCESS;
			case ACK_FAILED -> PlanPointState.FAILED;
			case ACK_TIMEOUT -> PlanPointState.TIMEOUT;
			default -> null;
		};
	}

}
