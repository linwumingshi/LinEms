package com.energyx.notify.channel;

import com.energyx.notify.model.NotifyConfigRow;
import org.springframework.stereotype.Component;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * 邮件渠道执行器：SMTP 发送（javax.mail 1.6.7，支持 SSL/STARTTLS）。
 *
 * <p>
 * channel_config 结构：{host, port, username, password, from, ssl}。
 * </p>
 */
@Component
public class EmailExecutor implements ChannelExecutor {

	@Override
	public String channel() {
		return NotifyChannel.EMAIL.getCode();
	}

	@Override
	public SendResult send(NotifyConfigRow config, String title, String content) {
		EmailCfg cfg;
		try {
			cfg = EmailCfg.parse(config.getChannelConfig());
		}
		catch (Exception e) {
			return SendResult.fail("邮件配置解析失败: " + e.getMessage());
		}
		if (cfg.host == null || cfg.host.isBlank() || cfg.username == null || cfg.from == null) {
			return SendResult.fail("邮件配置缺 host/username/from");
		}
		if (cfg.to == null || cfg.to.isBlank()) {
			return SendResult.fail("邮件配置缺收件人 to");
		}
		try {
			Properties props = new Properties();
			props.put("mail.smtp.host", cfg.host);
			props.put("mail.smtp.port", String.valueOf(cfg.port));
			props.put("mail.smtp.auth", "true");
			if (cfg.ssl) {
				props.put("mail.smtp.ssl.enable", "true");
			}
			else {
				props.put("mail.smtp.starttls.enable", "true");
			}
			props.put("mail.smtp.connectiontimeout", "10000");
			props.put("mail.smtp.timeout", "15000");
			Session session = Session.getInstance(props, new Authenticator() {
				@Override
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(cfg.username, cfg.password == null ? "" : cfg.password);
				}
			});
			MimeMessage msg = new MimeMessage(session);
			msg.setFrom(new InternetAddress(cfg.from));
			msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(cfg.to));
			msg.setSubject(title == null || title.isBlank() ? "EnergyX 通知" : title, "UTF-8");
			msg.setText(content == null ? "" : content, "UTF-8");
			Transport.send(msg);
			return SendResult.ok("邮件已发送至 " + cfg.to);
		}
		catch (Exception e) {
			return SendResult.fail("邮件发送异常: " + e.getMessage());
		}
	}

	/** channel_config JSON 投影（EMAIL 结构；to 为配置固定收件人，可空则由调用方 context.to 覆盖） */
	public static class EmailCfg {

		String host;

		int port = 465;

		String username;

		String password;

		String from;

		boolean ssl = true;

		String to;

		static EmailCfg parse(String json) throws Exception {
			var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
			EmailCfg c = new EmailCfg();
			if (node.has("host"))
				c.host = node.get("host").asText();
			if (node.has("port"))
				c.port = node.get("port").asInt();
			if (node.has("username"))
				c.username = node.get("username").asText();
			if (node.has("password"))
				c.password = node.get("password").asText();
			if (node.has("from"))
				c.from = node.get("from").asText();
			if (node.has("ssl"))
				c.ssl = node.get("ssl").asBoolean();
			if (node.has("to"))
				c.to = node.get("to").asText();
			return c;
		}

	}

}
