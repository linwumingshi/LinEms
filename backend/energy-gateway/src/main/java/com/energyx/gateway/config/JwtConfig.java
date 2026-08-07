package com.energyx.gateway.config;

import com.energyx.security.JwtProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关侧 JWT 配置绑定。
 * 与 energy-system 的 energyx.jwt.* 保持一致（HS 系列共享密钥 + issuer，算法由密钥长度决定）。
 */
@Configuration
public class JwtConfig {

    @Bean
    @ConfigurationProperties(prefix = "energyx.jwt")
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }
}
