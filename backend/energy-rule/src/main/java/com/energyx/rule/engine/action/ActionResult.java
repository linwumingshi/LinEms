package com.energyx.rule.engine.action;

/**
 * 动作执行结果（写入执行日志 action_result）。
 */
public record ActionResult(boolean success, String type, String message) {

	/** 成功结果 */
	public static ActionResult ok(String type, String message) {
		return new ActionResult(true, type, message);
	}

	/** 失败结果 */
	public static ActionResult fail(String type, String message) {
		return new ActionResult(false, type, message);
	}

}
