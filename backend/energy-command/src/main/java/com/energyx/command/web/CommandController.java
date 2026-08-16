package com.energyx.command.web;

import com.energyx.command.service.CommandService;
import com.energyx.command.web.dto.CommandView;
import com.energyx.command.web.dto.CreateCommandRequest;
import com.energyx.common.model.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指令中心 API。
 *
 * <ul>
 * <li>POST /api/command 创建指令（在线直发 / 离线入队，commandId 幂等）；</li>
 * <li>GET /api/command/{id} 指令状态查询。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/command")
public class CommandController {

	private final CommandService commandService;

	public CommandController(CommandService commandService) {
		this.commandService = commandService;
	}

	/**
	 * 创建指令。根据设备在线状态选择在线直发或离线入队；commandId 作为幂等键， 重复提交命中既有指令时直接返回已存在结果。
	 * @param request 请求体，字段说明见 {@link CreateCommandRequest}
	 * @return {@link Result}<{@link CommandView}> 创建后的指令视图；参数非法时返回 code=400 的失败结果
	 */
	@PostMapping
	public Result<CommandView> create(@Valid @RequestBody CreateCommandRequest request) {
		try {
			return Result.ok(commandService.createCommand(request));
		}
		catch (IllegalArgumentException e) {
			return Result.fail(400, e.getMessage());
		}
	}

	/**
	 * 查询指令状态。按 commandId 返回指令最新快照（含状态、执行结果、时间线等）。
	 * @param commandId 指令 ID（路径变量）
	 * @return {@link Result}<{@link CommandView}> 指令视图；指令不存在时返回 code=404 的失败结果
	 */
	@GetMapping("/{commandId}")
	public Result<CommandView> detail(@PathVariable String commandId) {
		CommandView view = commandService.getCommand(commandId);
		return view == null ? Result.fail(404, "指令不存在: " + commandId) : Result.ok(view);
	}

}
