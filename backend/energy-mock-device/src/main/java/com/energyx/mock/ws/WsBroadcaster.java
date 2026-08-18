package com.energyx.mock.ws;

/**
 * WebSocket 广播接口：业务层（SimulatorService）通过它把设备下行/日志实时推给前端。
 */
public interface WsBroadcaster {

	/** 向所有已连接的前端会话广播一条 JSON 事件 */
	void broadcast(String json);

}
