package com.energyx.notify.channel;

import com.energyx.notify.model.NotifyConfigRow;

/**
 * 渠道发送执行器接口（按 channel() 分发）。
 */
public interface ChannelExecutor {

	/** 返回本执行器负责的渠道编码（与 NotifyChannel.code 一致） */
	String channel();

	/** 发送通知；任何异常都应转为失败结果，不向上抛出 */
	SendResult send(NotifyConfigRow config, String title, String content);

}
