package com.energyx.tsdb;

import com.energyx.tsdb.config.TsdbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * energy-tsdb 启动入口。
 *
 * <p>时序写入服务只依赖 TDengine 与 Redis（幂等），无 MySQL DataSource；
 * 但 energy-common 传递引入 mybatis-plus/jdbc 自动配置，需排除
 * DataSource/MyBatis 三类自动配置，否则启动时因缺少数据源而失败。</p>
 */
@SpringBootApplication(
        scanBasePackages = "com.energyx",
        excludeName = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
                "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        })
@EnableConfigurationProperties(TsdbProperties.class)
@EnableScheduling
public class TsdbApplication {

    public static void main(String[] args) {
        SpringApplication.run(TsdbApplication.class, args);
    }
}
