package com.sanduo.energy.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一接入网关。
 * 路由表见 application.yml（lb:// 负载均衡指向注册在 Nacos 的微服务）。
 */
@SpringBootApplication
public class EnergyGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnergyGatewayApplication.class, args);
    }
}
