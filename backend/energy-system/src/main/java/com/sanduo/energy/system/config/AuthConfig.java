package com.sanduo.energy.system.config;

import com.sanduo.energy.security.JwtProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证相关 Bean：JWT 配置绑定 + 密码编码器。
 *
 * <p>密码编码器使用 Spring Security 的 DelegatingPasswordEncoder（支持 {bcrypt}、
 * {noop} 等前缀），兼容 V1 种子数据 {noop}admin123，同时支持生产 BCrypt 哈希。</p>
 */
@Configuration
public class AuthConfig {

    /** 绑定 sanduo.jwt.* 配置（secret/expire-seconds/issuer） */
    @Bean
    @ConfigurationProperties(prefix = "sanduo.jwt")
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }

    /** 委托密码编码器：默认 bcrypt，兼容 {prefix} 格式存量密码 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
