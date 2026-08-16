package com.energyx.ota.service;

import com.energyx.common.model.Result;
import com.energyx.ota.client.NotifyFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTA 运维告警通知（S4-3）：任务失败率激增自动暂停 / 灰度暂停等异常事件，向 notify 服务
 * 运维渠道（configCode=WEBHOOK_OPS）推送一条内容通知。
 *
 * <p>
 * 通知是旁路能力：notify 服务不可用仅记日志，不阻断 OTA 任务主流程。
 * </p>
 */
@Slf4j
@Service
public class OtaNotifyService {

	/** 运维渠道配置编码（es_notify.iot_notify_config 预置，WEBHOOK 本地测试） */
	private static final String OPS_CONFIG_CODE = "WEBHOOK_OPS";

	private final NotifyFeignClient notifyFeignClient;

	public OtaNotifyService(NotifyFeignClient notifyFeignClient) {
		this.notifyFeignClient = notifyFeignClient;
	}

	/**
	 * 推送 OTA 运维告警（content 直发，跳过模板渲染）。
	 * @param title 告警标题
	 * @param content 告警正文（支持 {} 占位符由调用方拼好）
	 */
	public void sendAlert(String title, String content) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("configCode", OPS_CONFIG_CODE);
			body.put("title", title);
			body.put("content", content);
			body.put("context", Map.of());
			Result<Map<String, Object>> r = notifyFeignClient.send(body);
			if (r != null && r.isSuccess()) {
				log.info("[OTA] 告警通知已推送 title={}", title);
			}
			else {
				log.warn("[OTA] 告警通知被拒 title={} resp={}", title, r == null ? "null" : r.getMessage());
			}
		}
		catch (Exception e) {
			log.warn("[OTA] 告警通知异常 title={}", title, e);
		}
	}

}
