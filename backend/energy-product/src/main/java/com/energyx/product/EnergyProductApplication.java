package com.energyx.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 产品域服务：产品品类 / 产品 / 物模型。
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.product.mapper")
public class EnergyProductApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnergyProductApplication.class, args);
	}

}
