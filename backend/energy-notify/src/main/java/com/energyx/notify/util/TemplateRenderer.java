package com.energyx.notify.util;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知模板占位符渲染器：{@code ${key}} 替换为 context 值；缺失 key 替换为空串。
 *
 * <p>
 * 支持嵌套对象取值：context 中 value 为 Map 时可点路径 {@code ${device.lastOnline}}。
 * </p>
 */
@Component
public class TemplateRenderer {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

	/** 渲染文本；text 为空返回空串；context 为 null 按空处理 */
	public String render(String text, Map<String, Object> context) {
		if (text == null || text.isEmpty())
			return "";
		Matcher m = PLACEHOLDER.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			Object val = resolve(m.group(1).trim(), context);
			m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private Object resolve(String path, Map<String, Object> context) {
		if (context == null || path.isEmpty())
			return null;
		String[] parts = path.split("\\.");
		Object cur = context;
		for (String part : parts) {
			if (!(cur instanceof Map<?, ?> map))
				return null;
			cur = map.get(part);
			if (cur == null)
				return null;
		}
		return cur;
	}

}
