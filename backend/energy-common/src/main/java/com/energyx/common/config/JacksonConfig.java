package com.energyx.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 配置：注册 JavaTimeModule（LocalDateTime 序列化），忽略 null 字段。
 * <p>
 * 雪花 ID 前端精度：Long.class（装箱 Long）序列化为字符串，避免 JS 精度失真。
 * 仅装箱 Long，不含 Long.TYPE（primitive long，如 PageResult.total / ts）保持数字。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        SimpleModule longAsString = new SimpleModule();
        longAsString.addSerializer(Long.class, ToStringSerializer.instance);
        return new ObjectMapper()
                .registerModule(longAsString)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
